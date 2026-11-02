package app.ft.iccoa

import app.ft.core.ProtoWriter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.KeyAgreement

class IccoaQuickAuthTest {
    private val pinnedCar = IccoaAuth.newKeyPair()

    private fun trustedResponse(request: ByteArray, wrongSignature: Boolean = false,
                                changedIdentity: Boolean = false): ByteArray {
        val fields = IccoaControl.Fields(request)
        val signer = if (changedIdentity) IccoaAuth.newKeyPair() else pinnedCar
        val agreement = IccoaAuth.newKeyPair()
        val ephemeral = IccoaAuth.publicBytes(agreement.public as ECPublicKey)
        val nonce = ByteArray(32) { (it + 1).toByte() }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(signer.private)
            update(ephemeral + fields.bytes(4) + nonce + fields.bytes(6))
            sign()
        }
        if (wrongSignature) signature[signature.lastIndex] =
            (signature.last().toInt() xor 1).toByte()
        return ProtoWriter().int32(1, 1793)
            .bytes(2, IccoaAuth.publicBytes(signer.public as ECPublicKey))
            .bytes(4, ephemeral).bytes(5, signature).bytes(6, nonce)
            .int32(7, 0).int32(8, 769).toByteArray()
    }

    private fun quick(): IccoaAuth = IccoaAuth(null, "trusted-phone", "SM-S916B",
        IccoaAuth.newKeyPair(), IccoaAuth.publicBytes(pinnedCar.public as ECPublicKey))

    @Test fun quickHasNoOldPinOrNormalIdentityOrUserConfirmed() {
        val request = IccoaControl.Fields(quick().request())
        assertTrue(request.bytes(2).isEmpty())
        assertTrue(request.bytes(3).isEmpty())
        assertEquals(0L, request.number(9))
        assertEquals(64, request.bytes(4).size)
        assertEquals(32, request.bytes(6).size)
        assertFalse(request.bytes(5).isEmpty())
    }

    @Test fun validQuickSignatureEstablishesFreshSessionWithoutPin() {
        val client = quick()
        val request = client.request()
        assertNotNull(client.accept(trustedResponse(request)))
        assertArrayEquals(IccoaAuth.publicBytes(pinnedCar.public as ECPublicKey),
            client.verifiedCarIdentity)
    }

    @Test fun missingServerIdentityIsAllowedOnlyWithPinnedTrust() {
        val client = quick()
        val req = client.request()
        val full = IccoaControl.Fields(trustedResponse(req))
        val noIdentity = ProtoWriter().int32(1, 1793).bytes(4, full.bytes(4))
            .bytes(5, full.bytes(5)).bytes(6, full.bytes(6))
            .int32(7, 0).int32(8, 769).toByteArray()
        assertNotNull(client.accept(noIdentity))
    }

    @Test fun changedCarIdentityAndTamperedSignatureFailClosed() {
        val changed = quick()
        val badKey = assertThrows(IccoaHandshakeException::class.java) {
            changed.accept(trustedResponse(changed.request(), changedIdentity = true))
        }
        assertEquals(IccoaHandshakeException.Reason.TRUSTED_IDENTITY_MISMATCH, badKey.reason)

        val tampered = quick()
        assertThrows(Exception::class.java) {
            tampered.accept(trustedResponse(tampered.request(), wrongSignature = true))
        }
    }

    @Test fun quickRejectRequiresFreshNormalAndNeverTriesOldCode() {
        for (reason in listOf(1L, 2L)) {
            val client = quick()
            val error = assertThrows(IccoaHandshakeException::class.java) {
                client.accept(ProtoWriter().int64(7, reason).toByteArray())
            }
            assertEquals(IccoaHandshakeException.Reason.RESPONSE_RESULT, error.reason)
            assertEquals(reason, error.result)
        }
    }

    @Test fun quickBleProfileDoesNotAdvertiseAnExpiredCode() {
        val profile = IccoaClientProfile("0f1234", "02:11:22:33:44:55")
        val json = JSONObject(profile.clientInfoQuick("SM-S916B", 36).toString(Charsets.UTF_8))
        assertFalse(json.has("pinCodeOrAuthentication"))
        assertEquals("0f1234", json.getString("id"))
        assertEquals(1002, json.getInt("type"))
        assertEquals(36, json.getInt("channel"))
    }

    @Test fun invalidQuickCombinationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            IccoaAuth("000001", "id", "phone", IccoaAuth.newKeyPair(),
                IccoaAuth.publicBytes(pinnedCar.public as ECPublicKey))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IccoaAuth(null, "id", "phone")
        }
    }
}
