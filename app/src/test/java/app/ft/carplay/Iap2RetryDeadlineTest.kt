package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2RetryDeadlineTest {
    private fun link() = Iap2LinkNegotiator.ConfirmedLink(
        listOf(Iap2LinkNegotiator.Session(7, 0, 2)), 0x9e, 0x42
    )

    @Test fun noPendingDataCannotStartOrRetransmit() {
        val window = Iap2ReliableLinkWindow(link())
        val deadline = Iap2RetryDeadline(window)
        assertFalse(deadline.onFirstSent(100))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.NOTHING_PENDING,
            deadline.poll(30000).status)
    }

    @Test fun scheduledRetriesUseMonotonicTimeAndBoundedCount() {
        val window = Iap2ReliableLinkWindow(link(), maxRetries = 2)
        val original = window.prepareData(7, byteArrayOf(1, 2)).wire!!
        val deadline = Iap2RetryDeadline(window, timeoutMs = 1200)
        assertTrue(deadline.onFirstSent(1000))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.WAITING_TIMEOUT,
            deadline.poll(2199).status)
        val first = deadline.poll(2200)
        assertEquals(Iap2ReliableLinkWindow.SendStatus.RETRY, first.status)
        assertTrue(original.contentEquals(first.wire!!))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.WAITING_TIMEOUT,
            deadline.poll(3399).status)
        val second = deadline.poll(3400)
        assertEquals(Iap2ReliableLinkWindow.SendStatus.RETRY, second.status)
        assertTrue(original.contentEquals(second.wire!!))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.RETRY_EXHAUSTED,
            deadline.poll(4600).status)
        assertEquals(Iap2ReliableLinkWindow.SendStatus.RETRY_EXHAUSTED,
            deadline.poll(10000).status)
        assertEquals(1, window.pendingCount())
    }

    @Test fun realAckCancelsAllFutureRetries() {
        val window = Iap2ReliableLinkWindow(link())
        window.prepareData(7, byteArrayOf(1))
        val deadline = Iap2RetryDeadline(window)
        assertTrue(deadline.onFirstSent(1000))
        val ack = window.observe(Iap2LinkFrameCodec.Frame(
            0x40, 0x43, 0x9e, 0, byteArrayOf()
        ))
        assertTrue(ack.ackOfOutstanding)
        assertEquals(Iap2ReliableLinkWindow.SendStatus.NOTHING_PENDING,
            deadline.poll(99999).status)
    }

    @Test fun wrongAckOrUnknownSessionDoesNotCancelRetry() {
        val window = Iap2ReliableLinkWindow(link())
        window.prepareData(7, byteArrayOf(1))
        val deadline = Iap2RetryDeadline(window)
        deadline.onFirstSent(1000)
        window.observe(Iap2LinkFrameCodec.Frame(0x40, 0x43, 0x9e, 8, byteArrayOf(1)))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.RETRY, deadline.poll(2200).status)
    }

    @Test fun resetClearsTimerWithoutChangingInFlightPacket() {
        val window = Iap2ReliableLinkWindow(link())
        window.prepareData(7, byteArrayOf(1))
        val deadline = Iap2RetryDeadline(window)
        deadline.onFirstSent(1000)
        deadline.reset()
        assertEquals(Iap2ReliableLinkWindow.SendStatus.WAITING_TIMEOUT,
            deadline.poll(100000).status)
        assertTrue(deadline.onFirstSent(200000))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.RETRY,
            deadline.poll(201200).status)
    }
}
