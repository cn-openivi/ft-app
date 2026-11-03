package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaTlsCiphertextCarryTest {
    @Test fun partialEncryptedTlsRecordSurvivesTwoUnderflows() {
        val buffer = AaTlsCiphertextCarry()
        val complete = byteArrayOf(0x17, 0x03, 0x03, 0, 5, 11, 22, 33, 44, 55)

        // First frame holds only a TLS record header.
        val first = buffer.next(complete.copyOfRange(0, 3))
        assertEquals(3, first.remaining())
        buffer.retain(first) // SSLEngine returned BUFFER_UNDERFLOW
        assertEquals(3, buffer.pendingBytes())

        // Next AA frame completes the header but not the TLS payload.
        val second = buffer.next(complete.copyOfRange(3, 7))
        assertEquals(7, second.remaining())
        assertEquals(0, buffer.pendingBytes())
        buffer.retain(second)
        assertEquals(7, buffer.pendingBytes())

        // Last frame completes the TLS record. Bytes must be bit-identical.
        val third = buffer.next(complete.copyOfRange(7, 10))
        val decryptedInput = ByteArray(third.remaining())
        third.get(decryptedInput)
        assertArrayEquals(complete, decryptedInput)
        buffer.retain(third)
        assertEquals(0, buffer.pendingBytes())
    }

    @Test fun alreadyConsumedTlsRecordIsNotReplayedBeforeNextPartialRecord() {
        val buffer = AaTlsCiphertextCarry()
        val first = byteArrayOf(1, 2, 3, 4, 5)
        val input = buffer.next(first)
        input.position(3) // 1..3 consumed by a complete TLS record.
        buffer.retain(input) // Remaining 4,5 are a fragment of next record.
        val next = buffer.next(byteArrayOf(6, 7))
        val carried = ByteArray(next.remaining())
        next.get(carried)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), carried)
    }

    @Test fun pendingCiphertextIsIsolatedPerAaSession() {
        val firstSession = AaTlsCiphertextCarry()
        firstSession.retain(firstSession.next(byteArrayOf(1, 2, 3)))
        assertEquals(3, firstSession.pendingBytes())
        val anotherSession = AaTlsCiphertextCarry()
        assertEquals(0, anotherSession.pendingBytes())
        val normal = anotherSession.next(byteArrayOf(4, 5))
        assertEquals(2, normal.remaining())
        assertEquals(4, normal.get().toInt() and 0xff)
    }

    @Test fun malformedOverlongUnfinishedRecordIsBounded() {
        val c = AaTlsCiphertextCarry(maxPending = 8)
        c.retain(c.next(byteArrayOf(1, 2, 3, 4, 5)))
        try {
            c.next(byteArrayOf(6, 7, 8, 9))
            fail("Oversized TLS ciphertext must fail closed")
        } catch (_: IllegalArgumentException) {
            assertEquals(5, c.pendingBytes())
        }
    }
}
