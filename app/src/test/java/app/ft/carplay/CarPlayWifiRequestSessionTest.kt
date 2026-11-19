package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayWifiRequestSessionTest {
    private val grouped = CarPlayWifiDialectPolicy.inspect(
        Iap2ControlSessionCodec.Message(0x1D01, listOf(
            Iap2ControlSessionCodec.Parameter(6, byteArrayOf(0x43, 0x01)),
            Iap2ControlSessionCodec.Parameter(7, byteArrayOf(0x43, 0x00)))))
    private val flat = CarPlayWifiDialectPolicy.inspect(
        Iap2ControlSessionCodec.Message(0x1D01, listOf(
            Iap2ControlSessionCodec.Parameter(6, byteArrayOf(0x57, 0x03)),
            Iap2ControlSessionCodec.Parameter(7, byteArrayOf(0x57, 0x02)))))
    private fun request(
        s: CarPlayWifiRequestSession, gen: Long,
        dialect: CarPlayWifiDialectPolicy.Selection,
        authenticated: Boolean = true, identity: Boolean = true,
        actualMac: Boolean = false, mac: String? = null
    ) = s.prepare(gen, dialect, authenticated, identity, actualMac, mac)

    @Test fun realNegotiatedFlatDialectPreparesOnlyZeroParamRequest() {
        val s = CarPlayWifiRequestSession()
        val p = request(s, 1, flat)
        assertEquals(CarPlayWifiRequestSession.Result.PREPARED_NOT_SENT, p.result)
        assertEquals(0x5702, p.message!!.id)
        assertTrue(p.message.parameters.isEmpty())
        assertArrayEquals(byteArrayOf(0x40, 0x40, 0, 6, 0x57, 0x02),
            Iap2ControlSessionCodec.encode(p.message))
        assertEquals(CarPlayWifiRequestSession.Result.REPLY_ID_MISMATCH,
            s.observeReply(1, true, Iap2ControlSessionCodec.Message(0x4301, emptyList())))
        assertEquals(CarPlayWifiRequestSession.Result.REPLY_OBSERVED_UNVERIFIED,
            s.observeReply(1, true, Iap2ControlSessionCodec.Message(0x5703, emptyList())))
        assertEquals(CarPlayWifiRequestSession.Result.REPLY_NOT_EXPECTED,
            s.observeReply(1, true, Iap2ControlSessionCodec.Message(0x5703, emptyList())))
    }

    @Test fun noAuthOrUnverifiedToyotaCannotPrepareAnyProtocol() {
        val s = CarPlayWifiRequestSession()
        assertEquals(CarPlayWifiRequestSession.Result.UNAUTHENTICATED,
            request(s, 1, flat, authenticated = false).result)
        assertEquals(CarPlayWifiRequestSession.Result.HU_IDENTITY_UNVERIFIED,
            request(s, 2, flat, identity = false).result)
        assertEquals(CarPlayWifiRequestSession.Result.REPLY_NOT_EXPECTED,
            s.observeReply(2, true, Iap2ControlSessionCodec.Message(0x5703, emptyList())))
    }

    @Test fun groupedAvailabilityNeedsRealLocallyVerifiedAddress() {
        val s = CarPlayWifiRequestSession()
        assertEquals(CarPlayWifiRequestSession.Result.VERIFIED_LOCAL_BLUETOOTH_IDENTITY_REQUIRED,
            request(s, 1, grouped).result)
        assertEquals(CarPlayWifiRequestSession.Result.VERIFIED_LOCAL_BLUETOOTH_IDENTITY_REQUIRED,
            request(s, 2, grouped, actualMac = true, mac = "02:00:00:00:00:00").result)
        val p = request(s, 3, grouped, actualMac = true, mac = "AA:BB:CC:DD:EE:FF")
        assertEquals(CarPlayWifiRequestSession.Result.PREPARED_NOT_SENT, p.result)
        assertEquals(0x4300, p.message!!.id)
        val parsed = Iap2ControlSessionCodec.decode(
            Iap2ControlSessionCodec.encode(p.message))!!
        assertEquals(2, parsed.parameters.size)
        assertFalse(p.toString().contains("AA:BB:CC"))
        assertEquals(CarPlayWifiRequestSession.Result.REPLY_OBSERVED_UNVERIFIED,
            s.observeReply(3, true, Iap2ControlSessionCodec.Message(0x4301, emptyList())))
    }

    @Test fun newAttemptAndLinkLossRevokePreviousReply() {
        val s = CarPlayWifiRequestSession()
        assertEquals(CarPlayWifiRequestSession.Result.PREPARED_NOT_SENT,
            request(s, 5, flat).result)
        assertEquals(CarPlayWifiRequestSession.Result.REQUEST_ALREADY_PREPARED,
            request(s, 5, flat).result)
        assertEquals(CarPlayWifiRequestSession.Result.INVALID_GENERATION,
            request(s, 4, flat).result)
        assertEquals(CarPlayWifiRequestSession.Result.UNAUTHENTICATED,
            request(s, 6, flat, authenticated = false).result)
        assertEquals(CarPlayWifiRequestSession.Result.REPLY_NOT_EXPECTED,
            s.observeReply(5, true, Iap2ControlSessionCodec.Message(0x5703, emptyList())))
        request(s, 7, flat)
        s.onLinkLost(6)
        assertEquals(CarPlayWifiRequestSession.Result.REPLY_OBSERVED_UNVERIFIED,
            s.observeReply(7, true, Iap2ControlSessionCodec.Message(0x5703, emptyList())))
        request(s, 8, flat)
        s.onLinkLost(8)
        assertEquals(CarPlayWifiRequestSession.Result.REPLY_NOT_EXPECTED,
            s.observeReply(8, true, Iap2ControlSessionCodec.Message(0x5703, emptyList())))
    }

    @Test fun untrustedReplyRevokesRequestWithoutBecomingAuthorizedSession() {
        val s = CarPlayWifiRequestSession()
        request(s, 1, flat)
        assertEquals(CarPlayWifiRequestSession.Result.REPLY_UNAUTHENTICATED,
            s.observeReply(1, false, Iap2ControlSessionCodec.Message(0x5703, emptyList())))
        assertEquals(CarPlayWifiRequestSession.Result.REPLY_NOT_EXPECTED,
            s.observeReply(1, true, Iap2ControlSessionCodec.Message(0x5703, emptyList())))
    }
}
