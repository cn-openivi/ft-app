package app.ft.carplay

/**
 * Packet-order observer, not an authenticator. It cannot produce an Apple
 * AuthenticationSuccess 0xAA05 or CarPlay ACTIVE, even if all inputs look
 * structurally valid. Platform/MFi entitlement and explicit OEM identity
 * policy remain independent, not provided by this source-only research.
 */
internal class Iap2AccessoryAuthenticationLedger {
    enum class Phase {
        WAIT_IDENTIFICATION, IDENTIFICATION_OBSERVED, CERTIFICATE_OBSERVED,
        TRUST_REVIEW_BLOCKED, TRUST_CHAIN_REVIEWED, CHALLENGE_OBSERVED,
        CRYPTO_PROOF_ONLY, REJECTED
    }

    enum class Reason {
        NONE, OUT_OF_ORDER, EMPTY_IDENTITY, EMPTY_CERTIFICATE,
        NO_APPROVED_TRUST, CHAIN_INVALID, CHALLENGE_INVALID,
        SIGNATURE_INVALID, UNTRUSTED_PROFILE
    }

    data class Snapshot(
        val phase: Phase,
        val reason: Reason,
        val observedMessageCount: Int,
        val mfiAuthenticated: Boolean = false
    )

    var snapshot = Snapshot(Phase.WAIT_IDENTIFICATION, Reason.NONE, 0)
        private set

    fun observeIdentification(message: Iap2ControlSessionCodec.Message): Snapshot {
        if (snapshot.phase != Phase.WAIT_IDENTIFICATION)
            return reject(Reason.OUT_OF_ORDER)
        if (message.id != 0x1D01 || message.parameters.isEmpty())
            return reject(Reason.EMPTY_IDENTITY)
        return set(Phase.IDENTIFICATION_OBSERVED)
    }

    fun observeCertificate(message: Iap2ControlSessionCodec.Message): Snapshot {
        if (snapshot.phase != Phase.IDENTIFICATION_OBSERVED)
            return reject(Reason.OUT_OF_ORDER)
        if (message.id != 0xAA01 || message.parameters.isEmpty() ||
            message.parameters.none { it.value.isNotEmpty() })
            return reject(Reason.EMPTY_CERTIFICATE)
        return set(Phase.CERTIFICATE_OBSERVED)
    }

    fun reviewCertificate(
        evidence: Iap2AccessoryCryptoEvidence.CertificateReview
    ): Snapshot {
        if (snapshot.phase != Phase.CERTIFICATE_OBSERVED)
            return reject(Reason.OUT_OF_ORDER)
        val valid = evidence.status ==
            Iap2AccessoryCryptoEvidence.CertificateStatus.CHAIN_VALID_REVOCATION_UNCHECKED
        return if (valid) set(Phase.TRUST_CHAIN_REVIEWED)
        else set(Phase.TRUST_REVIEW_BLOCKED, when (evidence.status) {
            Iap2AccessoryCryptoEvidence.CertificateStatus.NO_APPROVED_ANCHOR ->
                Reason.NO_APPROVED_TRUST
            else -> Reason.CHAIN_INVALID
        })
    }

    fun observeChallengeResponse(message: Iap2ControlSessionCodec.Message): Snapshot {
        if (snapshot.phase != Phase.TRUST_CHAIN_REVIEWED)
            return reject(Reason.OUT_OF_ORDER)
        if (message.id != 0xAA03 || message.parameters.none { it.value.isNotEmpty() })
            return reject(Reason.CHALLENGE_INVALID)
        return set(Phase.CHALLENGE_OBSERVED)
    }

    fun reviewSignature(
        status: Iap2AccessoryCryptoEvidence.SignatureStatus
    ): Snapshot {
        if (snapshot.phase != Phase.CHALLENGE_OBSERVED)
            return reject(Reason.OUT_OF_ORDER)
        return if (status ==
            Iap2AccessoryCryptoEvidence.SignatureStatus.CRYPTO_SIGNATURE_VALID_NOT_MFI)
            set(Phase.CRYPTO_PROOF_ONLY)
        else reject(if (status ==
            Iap2AccessoryCryptoEvidence.SignatureStatus.PROFILE_NOT_APPROVED)
            Reason.UNTRUSTED_PROFILE else Reason.SIGNATURE_INVALID)
    }

    fun reset() {
        snapshot = Snapshot(Phase.WAIT_IDENTIFICATION, Reason.NONE, 0)
    }

    private fun set(phase: Phase, reason: Reason = Reason.NONE): Snapshot {
        snapshot = Snapshot(phase, reason, snapshot.observedMessageCount + 1)
        return snapshot
    }

    private fun reject(reason: Reason): Snapshot = set(Phase.REJECTED, reason)
}
