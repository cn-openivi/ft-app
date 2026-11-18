package app.ft.carplay

import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertPathBuilder
import java.security.cert.CertificateFactory
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.CertStore
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate

/**
 * SOURCE-ONLY evidence validation for incoming iAP2 0xAA01/0xAA03.
 *
 * There is deliberately NO bundled Apple/MFi trust anchor. Certificates
 * extracted from the untrusted HU, public OEM firmware or test datasets may
 * never become anchors automatically. A successful PKIX path is not itself
 * evidence of an Apple-authorized MFi feature or a validated carrier call.
 */
internal object Iap2AccessoryCryptoEvidence {
    enum class CertificateStatus {
        NO_APPROVED_ANCHOR,
        EMPTY_OR_OVERSIZED,
        INVALID_CERTIFICATE_ENVELOPE,
        PKIX_REJECTED,
        CHAIN_VALID_REVOCATION_UNCHECKED
    }

    data class CertificateReview(
        val status: CertificateStatus,
        val certificateCount: Int = 0
    )

    fun verifyCertificatePath(
        wire: ByteArray,
        approvedAnchors: Set<X509Certificate> = emptySet()
    ): CertificateReview {
        if (approvedAnchors.isEmpty())
            return CertificateReview(CertificateStatus.NO_APPROVED_ANCHOR)
        if (wire.isEmpty() || wire.size > 65535)
            return CertificateReview(CertificateStatus.EMPTY_OR_OVERSIZED)
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            // The certificate wire may be PKCS#7 SignedData or a DER cert.
            // The platform factory handles the DER/PKCS#7 envelope; never
            // silently treat an arbitrary text blob as a trust certificate.
            val parsed = factory.generateCertificates(ByteArrayInputStream(wire))
                .filterIsInstance<X509Certificate>()
            if (parsed.isEmpty() || parsed.size > 8) {
                return CertificateReview(CertificateStatus.INVALID_CERTIFICATE_ENVELOPE)
            }
            val target = X509CertSelector().apply { certificate = parsed.first() }
            val params = PKIXBuilderParameters(
                approvedAnchors.map { TrustAnchor(it, null) }.toSet(), target
            )
            params.addCertStore(CertStore.getInstance(
                "Collection", CollectionCertStoreParameters(parsed)
            ))
            // Revocation check cannot be implied where no reliable verifier
            // configuration/online status is supplied: expose this limitation.
            params.isRevocationEnabled = false
            CertPathBuilder.getInstance("PKIX").build(params)
            CertificateReview(CertificateStatus.CHAIN_VALID_REVOCATION_UNCHECKED,
                parsed.size)
        } catch (_: Exception) {
            CertificateReview(CertificateStatus.PKIX_REJECTED)
        }
    }

    enum class SignatureProfile {
        NONE, SHA256_RSA, SHA256_ECDSA
    }

    enum class SignatureStatus {
        PROFILE_NOT_APPROVED, INVALID_INPUT, INVALID_SIGNATURE,
        CRYPTO_SIGNATURE_VALID_NOT_MFI
    }

    fun verifyChallengeResponse(
        publicKey: PublicKey?,
        challenge: ByteArray,
        response: ByteArray,
        profile: SignatureProfile = SignatureProfile.NONE
    ): SignatureStatus {
        if (profile == SignatureProfile.NONE) return SignatureStatus.PROFILE_NOT_APPROVED
        if (publicKey == null || challenge.size !in 16..64 ||
            response.isEmpty() || response.size > 1024)
            return SignatureStatus.INVALID_INPUT
        val algorithm = when (profile) {
            SignatureProfile.SHA256_RSA -> "SHA256withRSA"
            SignatureProfile.SHA256_ECDSA -> "SHA256withECDSA"
            SignatureProfile.NONE -> return SignatureStatus.PROFILE_NOT_APPROVED
        }
        return try {
            val signature = Signature.getInstance(algorithm)
            signature.initVerify(publicKey)
            signature.update(challenge.copyOf())
            if (signature.verify(response.copyOf()))
                SignatureStatus.CRYPTO_SIGNATURE_VALID_NOT_MFI
            else SignatureStatus.INVALID_SIGNATURE
        } catch (_: Exception) {
            SignatureStatus.INVALID_SIGNATURE
        }
    }
}
