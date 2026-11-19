package app.ft.carplay

import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2Ft200SafetyPolicyTest {
    private val crypto = Iap2AccessoryCryptoEvidence
    private val bond = CarPlaySingleBondPolicy
    private val audio = CarPlayAudioRolePolicy

    private fun msg(id: Int, value: ByteArray = byteArrayOf(1)) =
        Iap2ControlSessionCodec.Message(id, listOf(
            Iap2ControlSessionCodec.Parameter(0, value)
        ))

    @Test fun defaultMfiCertificateTrustStoreIsEmptyAndFailClosed() {
        assertEquals(Iap2AccessoryCryptoEvidence.CertificateStatus.NO_APPROVED_ANCHOR,
            crypto.verifyCertificatePath(byteArrayOf(0x30, 0x00)).status)
    }

    @Test fun syntheticSignatureNeedsExplicitAlgorithmAndNeverClaimsMfi() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val challenge = ByteArray(20) { it.toByte() }
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keys.private)
        signer.update(challenge)
        val response = signer.sign()
        assertEquals(Iap2AccessoryCryptoEvidence.SignatureStatus.PROFILE_NOT_APPROVED,
            crypto.verifyChallengeResponse(keys.public, challenge, response))
        assertEquals(Iap2AccessoryCryptoEvidence.SignatureStatus.CRYPTO_SIGNATURE_VALID_NOT_MFI,
            crypto.verifyChallengeResponse(keys.public, challenge, response,
                Iap2AccessoryCryptoEvidence.SignatureProfile.SHA256_RSA))
        assertEquals(Iap2AccessoryCryptoEvidence.SignatureStatus.INVALID_SIGNATURE,
            crypto.verifyChallengeResponse(keys.public, challenge + byteArrayOf(1), response,
                Iap2AccessoryCryptoEvidence.SignatureProfile.SHA256_RSA))
    }

    @Test fun authLedgerRejectsOutOfOrderAndUnknownTrust() {
        val ledger = Iap2AccessoryAuthenticationLedger()
        assertEquals(Iap2AccessoryAuthenticationLedger.Phase.REJECTED,
            ledger.observeCertificate(msg(0xAA01)).phase)
        ledger.reset()
        assertEquals(Iap2AccessoryAuthenticationLedger.Phase.IDENTIFICATION_OBSERVED,
            ledger.observeIdentification(msg(0x1D01)).phase)
        assertEquals(Iap2AccessoryAuthenticationLedger.Phase.CERTIFICATE_OBSERVED,
            ledger.observeCertificate(msg(0xAA01)).phase)
        assertEquals(Iap2AccessoryAuthenticationLedger.Phase.TRUST_REVIEW_BLOCKED,
            ledger.reviewCertificate(Iap2AccessoryCryptoEvidence.CertificateReview(
                Iap2AccessoryCryptoEvidence.CertificateStatus.NO_APPROVED_ANCHOR)).phase)
        assertFalse(ledger.snapshot.mfiAuthenticated)
    }

    @Test fun simulatedCryptoProofNeverAutomaticallyAuthenticatesAppleMfi() {
        val ledger = Iap2AccessoryAuthenticationLedger()
        ledger.observeIdentification(msg(0x1D01))
        ledger.observeCertificate(msg(0xAA01))
        ledger.reviewCertificate(Iap2AccessoryCryptoEvidence.CertificateReview(
            Iap2AccessoryCryptoEvidence.CertificateStatus.CHAIN_VALID_REVOCATION_UNCHECKED, 1))
        ledger.observeChallengeResponse(msg(0xAA03))
        assertEquals(Iap2AccessoryAuthenticationLedger.Phase.CRYPTO_PROOF_ONLY,
            ledger.reviewSignature(
                Iap2AccessoryCryptoEvidence.SignatureStatus.CRYPTO_SIGNATURE_VALID_NOT_MFI
            ).phase)
        assertFalse(ledger.snapshot.mfiAuthenticated)
    }

    @Test fun identityRequiresSameBondedHuNotClonedOrMaskedMac() {
        val selected = "14:22:33:44:55:66"
        val other = "16:22:33:44:55:66"
        fun check(hfp: String?, bonds: Set<String>?, connected: Boolean? = true) =
            bond.evaluate(CarPlaySingleBondPolicy.Snapshot(selected,bonds,hfp,connected))
        assertEquals(CarPlaySingleBondPolicy.Result.HFP_CONNECTED_SAME_HU,
            check(selected.lowercase(), setOf(selected)))
        assertEquals(CarPlaySingleBondPolicy.Result.HFP_CONNECTED_OTHER_HU,
            check(other, setOf(selected)))
        assertEquals(CarPlaySingleBondPolicy.Result.NO_EXISTING_BOND,
            check(selected, setOf(other)))
        assertEquals(CarPlaySingleBondPolicy.Result.BT_STATE_UNAVAILABLE,
            check(selected, null))
        assertEquals(CarPlaySingleBondPolicy.Result.HFP_DISCONNECTED,
            check(selected, setOf(selected), false))
        assertEquals(CarPlaySingleBondPolicy.Result.INVALID_HU_ADDRESS,
            bond.evaluate(CarPlaySingleBondPolicy.Snapshot(
                "02:00:00:00:00:00", setOf(selected), selected, true)))
    }

    @Test fun actualCarrierCallNeedsBothDirectionsAndRealFarEndSpeech() {
        val evidence = CarPlayCarrierCallEvidence()
        val hfp = CarPlaySingleBondPolicy.Result.HFP_CONNECTED_SAME_HU
        val oneWay = CarPlayCarrierCallEvidence.Observation(
            CarPlayCarrierCallEvidence.Direction.INCOMING, true, true, hfp,
            micToFarEndHeard = false, farEndToToyotaHeard = true, sessionGeneration = 7L)
        assertEquals(CarPlayCarrierCallEvidence.Status.MISSING_TWOWAY_SPEECH,
            evidence.record(oneWay))
        val correct = oneWay.copy(micToFarEndHeard = true)
        assertEquals(CarPlayCarrierCallEvidence.Status.INCOMING_VERIFIED_ONLY,
            evidence.record(correct))
        assertEquals(CarPlayCarrierCallEvidence.Status.BOTH_DIRECTIONS_VERIFIED,
            evidence.record(correct.copy(
                direction = CarPlayCarrierCallEvidence.Direction.OUTGOING)))
        evidence.reset()
        assertEquals(CarPlayCarrierCallEvidence.Status.NOT_VERIFIED, evidence.status())
    }

    @Test fun carrierEvidenceCannotCombineCallsAcrossReconnect() {
        val evidence = CarPlayCarrierCallEvidence()
        val hfp = CarPlaySingleBondPolicy.Result.HFP_CONNECTED_SAME_HU
        val first = CarPlayCarrierCallEvidence.Observation(
            CarPlayCarrierCallEvidence.Direction.INCOMING, true, true, hfp,
            micToFarEndHeard = true, farEndToToyotaHeard = true,
            sessionGeneration = 20L)
        assertEquals(CarPlayCarrierCallEvidence.Status.INCOMING_VERIFIED_ONLY,
            evidence.record(first))
        val newSession = first.copy(
            direction = CarPlayCarrierCallEvidence.Direction.OUTGOING,
            sessionGeneration = 21L)
        assertEquals(CarPlayCarrierCallEvidence.Status.OUTGOING_VERIFIED_ONLY,
            evidence.record(newSession))
        assertEquals(CarPlayCarrierCallEvidence.Status.STALE_OR_REVOKED_SESSION,
            evidence.record(first))
        assertEquals(CarPlayCarrierCallEvidence.Status.OUTGOING_VERIFIED_ONLY,
            evidence.status())
    }

    @Test fun failedFollowupCallWithdrawsEarlierAcceptance() {
        val evidence = CarPlayCarrierCallEvidence()
        val hfp = CarPlaySingleBondPolicy.Result.HFP_CONNECTED_SAME_HU
        val verified = CarPlayCarrierCallEvidence.Observation(
            CarPlayCarrierCallEvidence.Direction.INCOMING, true, true, hfp,
            micToFarEndHeard = true, farEndToToyotaHeard = true, sessionGeneration = 30L)
        evidence.record(verified)
        assertEquals(CarPlayCarrierCallEvidence.Status.MISSING_TWOWAY_SPEECH,
            evidence.record(verified.copy(
                direction = CarPlayCarrierCallEvidence.Direction.OUTGOING,
                farEndToToyotaHeard = false)))
        assertEquals(CarPlayCarrierCallEvidence.Status.MISSING_TWOWAY_SPEECH,
            evidence.status())
    }

    @Test fun lostOrUnidentifiedSessionNeverProducesCarrierPass() {
        val evidence = CarPlayCarrierCallEvidence()
        val hfp = CarPlaySingleBondPolicy.Result.HFP_CONNECTED_SAME_HU
        val verified = CarPlayCarrierCallEvidence.Observation(
            CarPlayCarrierCallEvidence.Direction.INCOMING, true, true, hfp,
            micToFarEndHeard = true, farEndToToyotaHeard = true, sessionGeneration = 40L)
        assertEquals(CarPlayCarrierCallEvidence.Status.SESSION_EVIDENCE_MISSING,
            evidence.record(verified.copy(sessionGeneration = 0L)))
        assertEquals(CarPlayCarrierCallEvidence.Status.INCOMING_VERIFIED_ONLY,
            evidence.record(verified))
        evidence.onSessionLost(40L)
        assertEquals(CarPlayCarrierCallEvidence.Status.NOT_VERIFIED,
            evidence.status())
        assertEquals(CarPlayCarrierCallEvidence.Status.STALE_OR_REVOKED_SESSION,
            evidence.record(verified))
        assertEquals(CarPlayCarrierCallEvidence.Status.INCOMING_VERIFIED_ONLY,
            evidence.record(verified.copy(sessionGeneration = 41L)))
    }

    private fun situation(
        call: Boolean = false,
        hfp: Boolean = true,
        siri: Boolean = false,
        nav: Boolean = false,
        transport: CarPlayAudioRolePolicy.Transport = CarPlayAudioRolePolicy.Transport.CARPLAY
    ) = CarPlayAudioRolePolicy.Situation(
        transport = transport, carrierCallActive = call,
        hfpToSelectedToyota = hfp,
        voiceAssistantActive = siri, navigationSpeaking = nav,
        projectionMediaPlaying = true, localRadioPlaying = true
    )

    @Test fun carrierCallAlwaysOverridesProjectionAndCannotCaptureCarrierMic() {
        val active = audio.evaluate(situation(call = true))
        assertEquals(CarPlayAudioRolePolicy.Owner.ANDROID_HFP, active.owner)
        assertFalse(active.allowProjectionMusic)
        assertFalse(active.allowProjectionMicCapture)
        val unknown = audio.evaluate(situation(call = true, hfp = false))
        assertEquals(CarPlayAudioRolePolicy.Owner.NONE, unknown.owner)
        assertEquals("CARRIER_ROUTE_NOT_VERIFIED", unknown.reason)
    }

    @Test fun navigationSiriAndThreeTransportsUseSingleAudioOwner() {
        assertEquals(CarPlayAudioRolePolicy.Role.SIRI,
            audio.evaluate(situation(siri = true, nav = true)).primaryRole)
        assertEquals(CarPlayAudioRolePolicy.Role.NAVIGATION,
            audio.evaluate(situation(nav = true)).primaryRole)
        for (t in listOf(CarPlayAudioRolePolicy.Transport.CARPLAY,
            CarPlayAudioRolePolicy.Transport.ICCOA,
            CarPlayAudioRolePolicy.Transport.BAIDU)) {
            val result = audio.evaluate(situation(transport = t))
            assertEquals(CarPlayAudioRolePolicy.Owner.PROJECTION, result.owner)
            assertTrue(result.allowProjectionMusic)
            assertFalse(result.allowProjectionMicCapture)
        }
        assertEquals(CarPlayAudioRolePolicy.Owner.TOYOTA_RADIO,
            audio.evaluate(CarPlayAudioRolePolicy.Situation(
                CarPlayAudioRolePolicy.Transport.NONE, false, false,
                false, false, false, true)).owner)
    }
}
