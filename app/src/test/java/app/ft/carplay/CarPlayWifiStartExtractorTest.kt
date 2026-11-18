package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayWifiStartExtractorTest {
    private fun c(value: String) = value.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    private fun tlv(id: Int, bytes: ByteArray): ByteArray {
        val size = bytes.size + 4
        return byteArrayOf((size ushr 8).toByte(),size.toByte(),
            (id ushr 8).toByte(),id.toByte()) + bytes
    }
    private fun start(ssid: String = "Toyota-Test",
                      secret: String = "SecretPskTest123",
                      type: Int = 2,
                      extra: ByteArray = byteArrayOf()) =
        Iap2ControlSessionCodec.Message(Iap2ControlSessionCodec.CARPLAY_START_SESSION,
            listOf(Iap2ControlSessionCodec.Parameter(1,
                tlv(0,c(ssid)) + tlv(1,c(secret)) +
                tlv(4,byteArrayOf(type.toByte())) + extra
            )))
    private val extractor = CarPlayWifiStartExtractor

    @Test fun noMfiIap2TrustNoAccessToWifiCredentialBytes() {
        val raw = start()
        val status = extractor.extract(raw,false,2)
        assertEquals(CarPlayWifiStartExtractor.Status.AUTHENTICATION_REQUIRED,status.status)
        assertNull(status.candidate)
        assertFalse(status.toString().contains("SecretPskTest123"))
    }

    @Test fun unknownActualSecurityEnumNeverAssumedFromOtherOem() {
        assertEquals(CarPlayWifiStartExtractor.Status.UNAPPROVED_WIRE_SECURITY,
            extractor.extract(start(),true,null).status)
        assertEquals(CarPlayWifiStartExtractor.Status.UNAPPROVED_WIRE_SECURITY,
            extractor.extract(start(),true,1).status)
        assertEquals(CarPlayWifiStartExtractor.Status.CANDIDATE_ONLY,
            extractor.extract(start(),true,2).status)
    }

    @Test fun wpa2LengthChecksAndRedactedCandidate() {
        val weak = extractor.extract(start(secret = "1234567"),true,2)
        assertEquals(CarPlayWifiStartExtractor.Status.INVALID_CREDENTIALS,weak.status)
        val valid = extractor.extract(start(),true,2)
        assertNotNull(valid.candidate)
        assertEquals("Toyota-Test",valid.candidate!!.ssid)
        assertFalse(valid.candidate.toString().contains("Toyota-Test"))
        assertFalse(valid.candidate.toString().contains("SecretPskTest123"))
    }

    @Test fun duplicateWifiFieldOrUnexpectedNestedFieldRejected() {
        val repeated = start(extra = tlv(1,c("ExtraSecret")))
        assertEquals(CarPlayWifiStartExtractor.Status.DUPLICATE_OR_UNKNOWN_FIELD,
            extractor.extract(repeated,true,2).status)
        val unknown = start(extra = tlv(66,byteArrayOf(1)))
        assertEquals(CarPlayWifiStartExtractor.Status.DUPLICATE_OR_UNKNOWN_FIELD,
            extractor.extract(unknown,true,2).status)
    }

    @Test fun malformedNestedAndUnknownMessageIdNeverStartsWifi() {
        val notStart = Iap2ControlSessionCodec.Message(0x4300,start().parameters)
        assertEquals(CarPlayWifiStartExtractor.Status.INVALID_START,
            extractor.extract(notStart,true,2).status)
        val broken = Iap2ControlSessionCodec.Message(0x4301,listOf(
            Iap2ControlSessionCodec.Parameter(1,byteArrayOf(0,1,0,0))
        ))
        assertEquals(CarPlayWifiStartExtractor.Status.INVALID_START,
            extractor.extract(broken,true,2).status)
    }
}
