package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayFlatWifiInfoExtractorTest {
    private val policy = CarPlayWifiDialectPolicy.inspect(
        Iap2ControlSessionCodec.Message(0x1D01, listOf(
            Iap2ControlSessionCodec.Parameter(6, byteArrayOf(0x57, 0x03)),
            Iap2ControlSessionCodec.Parameter(7, byteArrayOf(0x57, 0x02)))))
    private fun m(vararg values: Iap2ControlSessionCodec.Parameter) =
        Iap2ControlSessionCodec.Message(0x5703, values.toList())
    private fun value(id: Int, str: String) =
        Iap2ControlSessionCodec.Parameter(id, (str + "\u0000").toByteArray())
    private fun parse(
        msg: Iap2ControlSessionCodec.Message,
        auth: Boolean = true,
        securityApproved: Boolean = true
    ) = CarPlayFlatWifiInfoExtractor.extract(msg, policy, auth, securityApproved)

    @Test fun realFlatCredentialsAreOnlyUnconnectedCandidates() {
        val message = m(value(1, "Toyota-WiFi"), value(2, "password123"))
        val encoded = Iap2ControlSessionCodec.encode(message)
        val result = parse(Iap2ControlSessionCodec.decode(encoded)!!)
        assertEquals(CarPlayFlatWifiInfoExtractor.Status.CANDIDATE_NOT_CONNECTED, result.status)
        assertEquals("Toyota-WiFi", result.candidate!!.ssid)
        assertEquals("password123", result.candidate.passphrase)
        assertFalse(result.toString().contains("Toyota-WiFi"))
        assertFalse(result.candidate.toString().contains("password123"))
    }

    @Test fun notAuthenticatedAndSecurityNotApprovedFailClosed() {
        val message = m(value(1, "Toyota"), value(2, "abcdefgh"))
        assertEquals(CarPlayFlatWifiInfoExtractor.Status.AUTHENTICATION_REQUIRED,
            parse(message, auth = false).status)
        assertEquals(CarPlayFlatWifiInfoExtractor.Status.TOYOTA_PROFILE_UNVERIFIED,
            parse(message, securityApproved = false).status)
    }

    @Test fun wrongDialectAndNoTrustedIdentifierFail() {
        val grouped = CarPlayWifiDialectPolicy.inspect(
            Iap2ControlSessionCodec.Message(0x1D01, listOf(
                Iap2ControlSessionCodec.Parameter(6, byteArrayOf(0x43, 0x01)),
                Iap2ControlSessionCodec.Parameter(7, byteArrayOf(0x43, 0x00)))))
        val msg = m(value(1,"Toyota"), value(2,"abcdefgh"))
        assertEquals(CarPlayFlatWifiInfoExtractor.Status.WRONG_DIALECT_OR_MESSAGE,
            CarPlayFlatWifiInfoExtractor.extract(msg,grouped,true,true).status)
        assertEquals(CarPlayFlatWifiInfoExtractor.Status.WRONG_DIALECT_OR_MESSAGE,
            parse(Iap2ControlSessionCodec.Message(0x4301,msg.parameters)).status)
    }

    @Test fun rejectsUnknownFieldsDuplicatesAndOpaqueBssid() {
        val base = listOf(value(1,"Toyota"),value(2,"abcdefgh"))
        for (bad in listOf(
            m(*(base+value(1,"other")).toTypedArray()),
            m(*(base+value(4,"other")).toTypedArray()),
            m(*(base+value(3,"AA:BB:CC:DD:EE:FF")).toTypedArray())
        )) {
            assertEquals(CarPlayFlatWifiInfoExtractor.Status.INVALID_OR_DUPLICATE_FIELDS,
                parse(bad).status)
        }
        assertEquals(CarPlayFlatWifiInfoExtractor.Status.INVALID_OR_DUPLICATE_FIELDS,
            parse(m(value(1,"Toyota"),value(3,"XX"))).status)
        assertEquals(CarPlayFlatWifiInfoExtractor.Status.INVALID_OR_DUPLICATE_FIELDS,
            parse(m(value(1,"Toyota"))).status)
    }

    @Test fun rejectsInvalidUtf8MissingNullAndWeakPassword() {
        val corrupt = Iap2ControlSessionCodec.Parameter(1,
            byteArrayOf(0xC3.toByte(),0x28,0))
        for (bad in listOf(
            m(corrupt, value(2,"abcdefgh")),
            m(Iap2ControlSessionCodec.Parameter(1, "Toyota".toByteArray()),
                value(2,"abcdefgh")),
            m(value(1,"Toyota"),value(2,"123")),
            m(value(1,"Toyota"),value(2,"secret\npass"))
        )) {
            assertEquals(CarPlayFlatWifiInfoExtractor.Status.INVALID_CREDENTIALS,
                parse(bad).status)
        }
    }
}
