package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2ControlStreamParserTest {
    private val codec = Iap2ControlSessionCodec

    private fun message(id: Int, payload: ByteArray = byteArrayOf(1, 2, 3)): ByteArray =
        codec.encode(Iap2ControlSessionCodec.Message(id, listOf(Iap2ControlSessionCodec.Parameter(0x10, payload))))

    @Test fun splitHeaderAndTlvAreReassembledWithoutPartialSuccess() {
        val parser = Iap2ControlStreamParser()
        val full = message(codec.CARPLAY_START_SESSION)
        assertTrue(parser.accept(full.copyOfRange(0, 1)).isEmpty())
        assertTrue(parser.accept(full.copyOfRange(1, 5)).isEmpty())
        assertTrue(parser.accept(full.copyOfRange(5, 9)).isEmpty())
        val complete = parser.accept(full.copyOfRange(9, full.size))
        assertEquals(1, complete.size)
        assertEquals(codec.CARPLAY_START_SESSION, complete.single().id)
        assertEquals(0x10, complete.single().parameters.single().id)
    }

    @Test fun coalescedMessagesStayOrderedAndKeepOpaqueValues() {
        val parser = Iap2ControlStreamParser()
        val privateValue = "PRIVATE_AP_PASSWORD".toByteArray()
        val observed = parser.accept(
            message(codec.CARPLAY_AVAILABILITY, privateValue) +
                message(codec.CARPLAY_START_SESSION)
        )
        assertEquals(listOf(codec.CARPLAY_AVAILABILITY, codec.CARPLAY_START_SESSION),
            observed.map { it.id })
        assertTrue(observed[0].parameters[0].value.contentEquals(privateValue))
        assertFalse(codec.summarize(observed[0]).toString().contains("PRIVATE_AP_PASSWORD"))
        assertTrue(parser.accept(byteArrayOf()).isEmpty())
    }

    @Test fun corruptTlvIsSkippedBeforeResynchronizingOnValidMessage() {
        val parser = Iap2ControlStreamParser()
        val malformed = message(0x1234).apply {
            // Keep the message header length but invalidate the TLV size.
            this[6] = 0
            this[7] = 2
        }
        val valid = message(codec.CARPLAY_START_SESSION)
        val observed = parser.accept(byteArrayOf(0x7f, 0x40) + malformed + valid)
        assertEquals(1, observed.size)
        assertEquals(codec.CARPLAY_START_SESSION, observed.single().id)
    }

    @Test fun falseLargeHeaderCannotHideNextCompleteValidatedMessage() {
        val parser = Iap2ControlStreamParser()
        val corruptCandidate = byteArrayOf(0x40, 0x40, 0x40, 0x00, 0x12, 0x34)
        val valid = message(codec.CARPLAY_AVAILABILITY)
        val observed = parser.accept(corruptCandidate + valid)
        assertEquals(1, observed.size)
        assertEquals(codec.CARPLAY_AVAILABILITY, observed.single().id)
    }

    @Test fun validFragmentWithNoLaterCompleteFrameRemainsBuffered() {
        val parser = Iap2ControlStreamParser()
        val first = message(codec.CARPLAY_START_SESSION,
            ByteArray(31) { 0x40 })
        assertTrue(parser.accept(first.copyOfRange(0, 12)).isEmpty())
        assertEquals(1, parser.accept(first.copyOfRange(12, first.size)).size)
    }

    @Test fun corruptFrameAndValidFrameCanArriveInSeparateReadCalls() {
        val parser = Iap2ControlStreamParser()
        assertTrue(parser.accept(byteArrayOf(0x7f, 0x40, 0x40, 0x40, 0)).isEmpty())
        val actual = message(codec.CARPLAY_AVAILABILITY)
        val observed = parser.accept(byteArrayOf(12, 0x34) + actual)
        assertEquals(1, observed.size)
        assertEquals(codec.CARPLAY_AVAILABILITY, observed.single().id)
    }

    @Test fun partialMessagesCannotSurviveResetBetweenDifferentRealSessions() {
        val parser = Iap2ControlStreamParser()
        val full = message(codec.CARPLAY_START_SESSION)
        assertTrue(parser.accept(full.copyOfRange(0, 6)).isEmpty())
        parser.reset()
        assertTrue(parser.accept(full.copyOfRange(6, full.size)).isEmpty())
        assertEquals(codec.CARPLAY_START_SESSION, parser.accept(full).single().id)
    }

    @Test fun inputIsBoundedAndGarbageNeverCountsAsCarPlaySession() {
        val parser = Iap2ControlStreamParser()
        assertTrue(parser.accept(ByteArray(4096) { 0x55.toByte() }).isEmpty())
        assertTrue(runCatching { parser.accept(ByteArray(65_536)) }.isFailure)
        assertTrue(parser.accept(message(codec.CARPLAY_START_SESSION)).isNotEmpty())
    }
}
