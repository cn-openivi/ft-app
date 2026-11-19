package app.ft.supercast.adb

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Android ADB uses different TLS servers for code pairing and for the subsequent
 * _adb-tls-connect service. Their X509 DER hashes MUST NOT be compared.
 *
 * During the first verified local ADB session we learn the connect server's
 * public-key fingerprint. Trust is committed ONLY after a shell-UID-2000
 * Binder callback and ping have succeeded; later sessions fail closed if
 * the connect server presents another key. Self-signed certificates are
 * validated (including their signature and validity), never blindly accepted.
 */
internal object PeerCertificatePin {
    fun connectPublicKeySha256(presentedDer: ByteArray): ByteArray {
        require(presentedDer.isNotEmpty()) { "Missing ADB TLS certificate" }
        val cert = ByteArrayInputStream(presentedDer).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        cert.checkValidity()
        require(cert.subjectX500Principal == cert.issuerX500Principal) {
            "ADB TLS server certificate is not self-issued"
        }
        cert.verify(cert.publicKey)
        return MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
    }

    fun matchesConnectPublicKey(presentedDer: ByteArray, trustedKeySha256: ByteArray): Boolean {
        if (trustedKeySha256.size != 32) return false
        return MessageDigest.isEqual(connectPublicKeySha256(presentedDer), trustedKeySha256)
    }
}
