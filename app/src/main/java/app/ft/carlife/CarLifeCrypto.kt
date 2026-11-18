package app.ft.carlife

import android.util.Base64
import app.ft.core.DiagLog
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class CarLifeCrypto {
    @Volatile var started = false
    @Volatile private var key: SecretKeySpec? = null
    @Volatile private var incoming = false
    @Volatile private var outgoing = false
    private var decryptFailureLogged = false

    val active: Boolean get() = outgoing

    @Synchronized
    fun reset() {
        started = false
        key = null
        incoming = false
        outgoing = false
        decryptFailureLogged = false
    }

    @Synchronized
    fun aesKeyRequest(rsaPublicKeyBase64: String): String? {
        key = null
        incoming = false
        outgoing = false
        decryptFailureLogged = false
        return runCatching {
            val spki = Base64.decode(rsaPublicKeyBase64.trim(), Base64.NO_WRAP)
            val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki))
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val rnd = SecureRandom()
            val k = String(CharArray(16) { alphabet[rnd.nextInt(alphabet.length)] })
            val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            c.init(Cipher.ENCRYPT_MODE, pub)
            val wrapped = c.doFinal(k.toByteArray(Charsets.UTF_8))
            val request = Base64.encodeToString(wrapped, Base64.NO_WRAP)
            key = SecretKeySpec(k.toByteArray(Charsets.UTF_8), "AES")
            request
        }.getOrNull()
    }

    @Synchronized
    fun armIncoming() { incoming = true }

    @Synchronized
    fun enableOutgoing(): Boolean {
        if (key == null || !incoming || outgoing) return false
        outgoing = true
        return true
    }

    fun encryptOut(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) return payload
        val k = synchronized(this) { if (outgoing) key else null } ?: return payload
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, k)
        return c.doFinal(payload)
    }

    fun decryptIn(payload: ByteArray, allowPlainHandshake: Boolean = false): ByteArray {
        if (payload.isEmpty()) return payload
        val state = synchronized(this) { Triple(incoming, outgoing, key) }
        if (!state.first) return payload // Negotiated plaintext mode or pre-key exchange.
        val k = state.third ?: throw IllegalStateException("Baidu incoming AES armed without key")
        return try {
            val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, k)
            c.doFinal(payload)
        } catch (e: java.security.GeneralSecurityException) {
            // Only the OEM's AES-receipt acknowledgement may be plaintext
            // between key-send and ENCRYPT_READY. After READY, ALL corrupt
            // encrypted command/control frames fail closed.
            if (allowPlainHandshake && !state.second) return payload
            val report = synchronized(this) {
                if (key !== k || decryptFailureLogged) false
                else { decryptFailureLogged = true; true }
            }
            if (report) DiagLog.w("CarLife", "incoming AES rejected: ${e.javaClass.simpleName}; no plaintext fallback")
            throw e
        }
    }
}
