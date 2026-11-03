package app.ft.aa

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/** Exact FT AA wire-frame boundaries and reassembly regression. */
class AaFramingTest {
    private fun payload(size: Int): ByteArray =
        ByteArray(size) { i -> (i * 17 + 3).toByte() }

    private fun encode(bytes: ByteArray, channel: Int = AaProtocol.CH_VIDEO) =
        AaFraming.encode(channel, bytes, encrypted = false, control = false) { it }

    private fun parse(bytes: ByteArray): AaFraming.Frame =
        AaFraming.readFrame(ByteArrayInputStream(bytes))

    @Test fun lastByteBelowAndExactlyAtSixteenKUsesBulkFrame() {
        for (size in listOf(AaProtocol.MAX_FRAME_PAYLOAD - 1, AaProtocol.MAX_FRAME_PAYLOAD)) {
            val source = payload(size)
            val frames = encode(source)
            assertEquals("fragment count for $size", 1, frames.size)
            val raw = frames.single()
            assertEquals(AaProtocol.CH_VIDEO, raw[0].toInt() and 0xff)
            assertEquals(AaProtocol.FRAME_BULK, raw[1].toInt() and 3)
            assertEquals((size ushr 8) and 255, raw[2].toInt() and 255)
            assertEquals(size and 255, raw[3].toInt() and 255)
            assertEquals(size + 4, raw.size)
            val assembled = AaFraming.Assembler { it }.feed(parse(raw))
            assertNotNull(assembled)
            assertArrayEquals(source, assembled!!.payload)
        }
    }

    @Test fun firstByteAboveSixteenKHasExactFirstAndLastHeaders() {
        val source = payload(AaProtocol.MAX_FRAME_PAYLOAD + 1)
        val wires = encode(source)
        assertEquals(2, wires.size)
        assertEquals(AaProtocol.FRAME_FIRST, wires[0][1].toInt() and 3)
        assertEquals(AaProtocol.FRAME_LAST, wires[1][1].toInt() and 3)
        assertEquals(8 + AaProtocol.MAX_FRAME_PAYLOAD, wires[0].size)
        assertEquals(5, wires[1].size)
        val total = source.size
        assertEquals((total ushr 24) and 255, wires[0][4].toInt() and 255)
        assertEquals((total ushr 16) and 255, wires[0][5].toInt() and 255)
        assertEquals((total ushr 8) and 255, wires[0][6].toInt() and 255)
        assertEquals(total and 255, wires[0][7].toInt() and 255)
        val assembler = AaFraming.Assembler { it }
        assertNull(assembler.feed(parse(wires[0])))
        assertArrayEquals(source, assembler.feed(parse(wires[1]))!!.payload)
    }

    @Test fun middleAndLastWithoutFirstMustNotCreateAnAAMessage() {
        val parser = AaFraming.Assembler { it }
        assertNull(parser.feed(AaFraming.Frame(3, AaProtocol.FRAME_MIDDLE, payload(100))))
        assertNull(parser.feed(AaFraming.Frame(3, AaProtocol.FRAME_LAST, payload(100))))
        val good = payload(24)
        assertArrayEquals(good,
            parser.feed(AaFraming.Frame(3, AaProtocol.FRAME_BULK, good))!!.payload)
    }

    @Test fun validInterleavingAcrossSensorAndVideoChannelsIsPreserved() {
        val a = encode(payload(50_000), AaProtocol.CH_VIDEO).map(::parse)
        val b = encode(payload(33_000), AaProtocol.CH_SENSOR).map(::parse)
        assertTrue(a.size > 2 && b.size > 2)
        val assembler = AaFraming.Assembler { it }
        assertNull(assembler.feed(a[0]))
        assertNull(assembler.feed(b[0]))
        for (i in 1 until a.lastIndex) assertNull(assembler.feed(a[i]))
        for (i in 1 until b.lastIndex) assertNull(assembler.feed(b[i]))
        assertArrayEquals(payload(50_000), assembler.feed(a.last())!!.payload)
        assertArrayEquals(payload(33_000), assembler.feed(b.last())!!.payload)
    }

    @Test fun newBulkFrameAbandonsIncompletePriorMessageWithoutMerging() {
        val assembler = AaFraming.Assembler { it }
        assertNull(assembler.feed(AaFraming.Frame(3, AaProtocol.FRAME_FIRST, payload(1024))))
        val newMessage = payload(33)
        assertArrayEquals(newMessage,
            assembler.feed(AaFraming.Frame(3, AaProtocol.FRAME_BULK, newMessage))!!.payload)
        assertNull(assembler.feed(AaFraming.Frame(3, AaProtocol.FRAME_LAST, payload(11))))
    }

    @Test fun encryptedChunkTransformerIsAppliedOnEverySegment() {
        val plain = payload(32_769)
        val xor: (ByteArray) -> ByteArray =
            { bytes -> ByteArray(bytes.size) { i -> (bytes[i].toInt() xor 0x55).toByte() } }
        val wires = AaFraming.encode(
            AaProtocol.CH_VIDEO, plain,
            encrypted = true, control = false, transform = xor
        )
        val assembler = AaFraming.Assembler(xor)
        val messages = wires.mapNotNull { assembler.feed(parse(it)) }
        assertEquals(1, messages.size)
        assertTrue(messages.single().encrypted)
        assertArrayEquals(plain, messages.single().payload)
    }
}
