package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2ControlStreamParserTest {
    private val codec = Iap2ControlSessionCodec

    private fun message(id: Int, payload: ByteArray = byteArrayOf(1, 2, 3)): ByteArray =
        codec.encode(codec.Message(id, listOf(codec.Parameter(0x10, payload))))

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
