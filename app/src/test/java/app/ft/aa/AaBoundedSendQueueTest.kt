package app.ft.aa

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AaBoundedSendQueueTest {
    @Test fun queueNeverExceedsCapacityAndEvictsOptionalForGps() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sent = mutableListOf<String>()
        val dropped = AtomicInteger(0)
        val q = AaBoundedSendQueue(2, onDropped = { dropped.set(it) })
        try {
            assertTrue(q.offer(true) { running.countDown(); release.await(3, TimeUnit.SECONDS) })
            assertTrue(running.await(2, TimeUnit.SECONDS))
            assertTrue(q.offer(false) { synchronized(sent) { sent.add("optional1") } })
            assertTrue(q.offer(false) { synchronized(sent) { sent.add("optional2") } })
            assertEquals(2, q.queued())
            assertTrue(q.offer(true) { synchronized(sent) { sent.add("gps") } })
            assertEquals(2, q.queued())
            assertEquals(1, dropped.get())
            release.countDown()
            Thread.sleep(200)
            assertTrue(synchronized(sent) { "gps" in sent })
        } finally {
            release.countDown()
            q.close()
        }
    }

    @Test fun allCriticalSaturationReportsFailureInsteadOfSilentDrop() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val q = AaBoundedSendQueue(1)
        try {
            assertTrue(q.offer(true) { entered.countDown(); release.await(3, TimeUnit.SECONDS) })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(q.offer(true) {})
            assertFalse(q.offer(true) {})
            assertEquals(1, q.queued())
        } finally {
            release.countDown()
            q.close()
        }
    }

    @Test fun staleSessionCancelsPendingWritesAfterClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executed = AtomicInteger(0)
        val q = AaBoundedSendQueue(3)
        assertTrue(q.offer(true) { entered.countDown(); release.await(3, TimeUnit.SECONDS) })
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(q.offer(false) { executed.incrementAndGet() })
        q.close()
        release.countDown()
        Thread.sleep(100)
        assertEquals(0, executed.get())
        assertTrue(q.isStopped())
        assertFalse(q.offer(true) { executed.incrementAndGet() })
    }

    @Test fun sensorAndVideoAcksRemainHighPriority() {
        assertTrue(AaSendPriority.critical(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_EVENT_INDICATION))
        assertTrue(AaSendPriority.critical(AaProtocol.CH_CONTROL, AaProtocol.PING_RESPONSE))
        assertTrue(AaSendPriority.critical(AaProtocol.CH_VIDEO, AaProtocol.VIDEO_FOCUS_INDICATION))
        assertTrue(AaSendPriority.critical(AaProtocol.CH_MEDIA_AUDIO, AaProtocol.AV_MEDIA_ACK))
        assertFalse(AaSendPriority.critical(AaProtocol.CH_MEDIA_STATUS, 0x8001))
    }
}
