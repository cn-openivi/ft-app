package app.ft.supercast.adb

import java.security.MessageDigest

/** Accept only the exact certificate learned in the authenticated pairing session. */
internal object PeerCertificatePin {
    fun matches(presentedDer: ByteArray, pairedSha256: ByteArray): Boolean {
        if (presentedDer.isEmpty() || pairedSha256.size != 32) return false
        val actual = MessageDigest.getInstance("SHA-256").digest(presentedDer)
        return MessageDigest.isEqual(actual, pairedSha256)
    }
}
