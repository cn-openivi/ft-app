package app.ft.carplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2ControlSessionCodecTest {
    private val codec = Iap2ControlSessionCodec

    @Test fun availabilityPacketUsesPhoneOutboundMessageIdAndFullTlvLength() {
        val message = Iap2ControlSessionCodec.Message(
            codec.CARPLAY_AVAILABILITY,
            listOf(Iap2ControlSessionCodec.Parameter(0x100, byteArrayOf(1, 2)), Iap2ControlSessionCodec.Parameter(0x101, byteArrayOf()))
        )
        val wire = codec.encode(message)
        assertEquals(16, wire.size)
        assertArrayEquals(byteArrayOf(0x40, 0x40, 0, 16, 0x43, 0), wire.copyOfRange(0, 6))
        val parsed = codec.decode(wire)!!
        assertEquals(codec.CARPLAY_AVAILABILITY, parsed.id)
        assertEquals(Iap2ControlSessionCodec.PhoneSideRole.OUTBOUND_AVAILABILITY, codec.classify(parsed.id))
        assertEquals(2, parsed.parameters.size)
        assertEquals(0x100, parsed.parameters[0].id)
        assertArrayEquals(byteArrayOf(1, 2), parsed.parameters[0].value)
        assertTrue(parsed.parameters[1].value.isEmpty())
    }

    @Test fun toyotaStartSessionIsInboundAndNotYetWifiReady() {
        val encoded = codec.encode(Iap2ControlSessionCodec.Message(
            codec.CARPLAY_START_SESSION,
            listOf(Iap2ControlSessionCodec.Parameter(0x10, byteArrayOf(12, 13, 14)))
        ))
        val decoded = codec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(Iap2ControlSessionCodec.PhoneSideRole.INBOUND_START_SESSION, codec.classify(decoded!!.id))
        assertTrue(codec.isStructurallyValidStartSession(decoded))
        // A lone 0x4301 without TLVs cannot satisfy a physical Wi-Fi handover gate.
        assertFalse(codec.isStructurallyValidStartSession(
            Iap2ControlSessionCodec.Message(codec.CARPLAY_START_SESSION, emptyList())
        ))
        assertFalse(codec.isStructurallyValidStartSession(
            Iap2ControlSessionCodec.Message(codec.CARPLAY_AVAILABILITY, decoded.parameters)
        ))
    }

    @Test fun malformedMagicLengthAndParameterBoundariesFailClosed() {
        val clean = codec.encode(Iap2ControlSessionCodec.Message(0x4301, listOf(Iap2ControlSessionCodec.Parameter(2, byteArrayOf(3)))))
        assertNull(codec.decode(byteArrayOf(0x40, 0x40, 0, 5, 0x43, 1)))
        assertNull(codec.decode(clean.clone().also { it[0] = 0 }))
        assertNull(codec.decode(clean.clone().also { it[2] = 0; it[3] = 7 }))
        assertNull(codec.decode(clean.clone().also { it[6] = 0; it[7] = 2 }))
        assertNull(codec.decode(clean.clone().also { it[6] = 0; it[7] = 40 }))
        assertNull(codec.decode(clean + byteArrayOf(1)))
    }

    @Test fun messageAndParameterBoundsRejectOversizedPayloads() {
        assertTrue(runCatching {
            codec.encode(Iap2ControlSessionCodec.Message(0x4300, listOf(Iap2ControlSessionCodec.Parameter(1, ByteArray(65532)))))
        }.isFailure)
        assertTrue(runCatching {
            codec.encode(Iap2ControlSessionCodec.Message(-1, emptyList()))
        }.isFailure)
        assertTrue(runCatching {
            codec.encode(Iap2ControlSessionCodec.Message(0, listOf(Iap2ControlSessionCodec.Parameter(65536, byteArrayOf()))))
        }.isFailure)
    }

    @Test fun diagnosticSummaryNeverContainsRealWifiCredentials() {
        val secret = "PRIVATE_WIFI_PASSPHRASE"
        val msg = Iap2ControlSessionCodec.Message(0x4301, listOf(Iap2ControlSessionCodec.Parameter(4, secret.toByteArray())))
        val summary = codec.summarize(msg)
        assertEquals(Iap2ControlSessionCodec.PhoneSideRole.INBOUND_START_SESSION, summary.role)
        assertEquals(listOf(4 to secret.length), summary.parameterIdsAndSizes)
        assertFalse(summary.toString().contains(secret))
        assertEquals(Iap2ControlSessionCodec.PhoneSideRole.OTHER, codec.classify(0x1234))
    }
}
