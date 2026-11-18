package app.ft.carplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LinkFrameCodecTest {
    @Test fun headerOnlyAckRoundTripAndAdditiveChecksum() {
        val wire = Iap2LinkFrameCodec.encode(
            control = 0x40, sequence = 0x12, acknowledgement = 0x34, session = 0
        )
        assertEquals(9, wire.size)
        assertEquals(0xff, wire[0].toInt() and 0xff)
        assertEquals(0x5a, wire[1].toInt() and 0xff)
        assertEquals(0, wire.sumOf { it.toInt() and 0xff } and 0xff)
        val decoded = Iap2LinkFrameCodec.decode(wire)!!
        assertEquals(0x40, decoded.control)
        assertEquals(0x12, decoded.sequence)
        assertEquals(0x34, decoded.acknowledgement)
        assertTrue(decoded.payload.isEmpty())
    }

    @Test fun payloadHasSeparateChecksumAndExactWireLength() {
        val payload = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())
        val wire = Iap2LinkFrameCodec.encode(0, 7, 6, 1, payload)
        assertEquals(HEADER_PLUS_PAYLOAD_AND_CHECKSUM, wire.size)
        assertEquals(0, wire.copyOfRange(9, wire.size).sumOf { it.toInt() and 0xff } and 0xff)
        assertArrayEquals(payload, Iap2LinkFrameCodec.decode(wire)!!.payload)
    }

    @Test fun malformedHeadersLengthsAndPayloadChecksumsAreRejected() {
        val valid = Iap2LinkFrameCodec.encode(0x40, 0, 1, 0)
        assertNull(Iap2LinkFrameCodec.decode(valid.copyOf(8)))
        assertNull(Iap2LinkFrameCodec.decode(valid.clone().apply { this[1] = 0x00 }))
        assertNull(Iap2LinkFrameCodec.decode(valid.clone().apply { this[3] = 100 }))
        assertNull(Iap2LinkFrameCodec.decode(valid.clone().apply { this[8] = 0 }))
        val withData = Iap2LinkFrameCodec.encode(0, 1, 2, 0, byteArrayOf(11, 12))
        assertNull(Iap2LinkFrameCodec.decode(withData.clone().apply { this[9] = 99 }))
    }

    @Test fun streamResynchronizesGarbageAndFragmentedFrames() {
        val parser = Iap2LinkStreamParser()
        val a = Iap2LinkFrameCodec.encode(0x40, 1, 2, 0)
        val b = Iap2LinkFrameCodec.encode(0, 3, 4, 1, byteArrayOf(10, 20))
        assertTrue(parser.accept(byteArrayOf(7, 8) + a.copyOfRange(0, 3)).isEmpty())
        assertTrue(parser.accept(a.copyOfRange(3, 7)).isEmpty())
        val decoded = parser.accept(a.copyOfRange(7, a.size) + b)
        assertEquals(2, decoded.size)
        assertEquals(1, decoded[0].sequence)
        assertArrayEquals(byteArrayOf(10, 20), decoded[1].payload)
        assertTrue(parser.accept(byteArrayOf()).isEmpty())
    }

    @Test fun readerRejectsBadChecksumAndRecovers() {
        val parser = Iap2LinkStreamParser()
        val good = Iap2LinkFrameCodec.encode(0x40, 2, 1, 0)
        val damaged = good.clone().apply { this[8] = 0 }
        val result = parser.accept(damaged + good)
        assertEquals(1, result.size)
        assertEquals(2, result[0].sequence)
    }

    @Test fun invalidEncodingInputsFailClosed() {
        val negative = runCatching { Iap2LinkFrameCodec.encode(-1, 0, 0, 0) }
        assertTrue(negative.isFailure)
        val oversized = runCatching {
            Iap2LinkFrameCodec.encode(0, 0, 0, 0, ByteArray(65_526))
        }
        assertTrue(oversized.isFailure)
        assertFalse(Iap2LinkFrameCodec.declaredLength(byteArrayOf(0xff.toByte(), 0x5a, 0, 0), 0) != null)
    }

    companion object {
        private const val HEADER_PLUS_PAYLOAD_AND_CHECKSUM = 9 + 5 + 1
    }
}
