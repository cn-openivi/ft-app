package app.ft.projection

import android.media.AudioRecord
import android.media.projection.MediaProjection
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioCaptureTest {
    @Test fun mirrorCaptureOnlyRequestsAndroidPermittedPlaybackUsages() {
        // AOSP Android 12: only UNKNOWN=0, MEDIA=1 and GAME=14 can be
        // captured from another app; NAVIGATION_GUIDANCE=12 cannot.
        assertEquals(listOf(1, 14, 0), AudioCapture.capturableUsages)
        assertFalse(AudioCapture.capturableUsages.contains(12))
        // Never confuse Android Auto's independent SPEECH audio channel
        // with third-party MediaProjection playback capture.
        assertFalse(AudioCapture.capturableUsages.contains(2)) // VOICE_COMMUNICATION
        assertEquals(3, AudioCapture.capturableUsages.distinct().size)
    }

    private val projection = mock(MediaProjection::class.java)

    private fun await(latch: CountDownLatch) {
        assertTrue("capture thread did not finish in time", latch.await(5, TimeUnit.SECONDS))
    }

    private fun failure(throws: Boolean) {
        val record = mock(AudioRecord::class.java)
        val released = CountDownLatch(1)
        doAnswer { released.countDown(); null }.`when`(record).release()
        `when`(record.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            if (throws) throw IllegalStateException("dead recorder")
            AudioRecord.ERROR_DEAD_OBJECT
        }
        val capture = AudioCapture({ record }, { fail("failed reads must not deliver PCM") })
        assertTrue(capture.start(projection))
        await(released)
        assertFalse(capture.active)
        capture.stop()
        verify(record, times(1)).release()
    }

    @Test fun zeroReadsAreRateLimitedWithoutPublishingPcm() {
        val record = mock(AudioRecord::class.java)
        val released = CountDownLatch(1)
        val pauses = AtomicInteger()
        val reads = AtomicInteger()
        val delivered = AtomicInteger()
        doAnswer { released.countDown(); null }.`when`(record).release()
        `when`(record.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            if (reads.incrementAndGet() <= 3) 0 else AudioRecord.ERROR_DEAD_OBJECT
        }
        val capture = AudioCapture(
            { record },
            { delivered.incrementAndGet() },
            { pauses.incrementAndGet() }
        )
        assertTrue(capture.start(projection))
        await(released)
        assertEquals(4, reads.get())
        assertEquals(3, pauses.get())
        assertEquals(0, delivered.get())
        assertFalse(capture.active)
        capture.stop()
        verify(record, times(1)).release()
    }

    @Test fun stopDuringEmptyReadPauseDoesNotRetryOrPublishPcm() {
        val record = mock(AudioRecord::class.java)
        val inPause = CountDownLatch(1)
        val resumePause = CountDownLatch(1)
        val reads = AtomicInteger()
        val delivered = AtomicInteger()
        `when`(record.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            reads.incrementAndGet()
            0
        }
        val capture = AudioCapture(
            { record },
            { delivered.incrementAndGet() },
            { inPause.countDown(); await(resumePause) }
        )
        try {
            assertTrue(capture.start(projection))
            await(inPause)
            capture.stop()
        } finally {
            resumePause.countDown()
        }
        assertEquals(1, reads.get())
        assertEquals(0, delivered.get())
        assertFalse(capture.active)
        verify(record, times(1)).release()
    }

    @Test fun negativeReadClearsActiveAndReleasesRecord() = failure(false)
    @Test fun throwingReadClearsActiveAndReleasesRecord() = failure(true)

    @Test fun failedCaptureCanRestart() {
        val first = mock(AudioRecord::class.java)
        val second = mock(AudioRecord::class.java)
        val released = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        doAnswer { released.countDown(); null }.`when`(first).release()
        `when`(first.read(any(ByteArray::class.java), anyInt(), anyInt())).thenReturn(-1)
        `when`(second.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            reading.countDown(); await(finish); -1
        }
        val records = ArrayDeque(listOf(first, second))
        val capture = AudioCapture({ records.removeFirst() }, {})
        try {
            assertTrue(capture.start(projection))
            await(released)
            assertTrue(capture.start(projection))
            await(reading)
            assertTrue(capture.active)
        } finally {
            capture.stop()
            finish.countDown()
        }
        verify(first, times(1)).release()
        verify(second, times(1)).release()
    }

    @Test fun oldReadAfterStopCannotPublishOrDisableReplacement() {
        val first = mock(AudioRecord::class.java)
        val second = mock(AudioRecord::class.java)
        val oldReading = CountDownLatch(1)
        val newReading = CountDownLatch(1)
        val finishOld = CountDownLatch(1)
        val finishNew = CountDownLatch(1)
        var oldThread: Thread? = null
        val delivered = AtomicInteger()
        `when`(first.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            oldThread = Thread.currentThread()
            oldReading.countDown(); await(finishOld); 4
        }
        `when`(second.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            newReading.countDown(); await(finishNew); -1
        }
        val records = ArrayDeque(listOf(first, second))
        val capture = AudioCapture({ records.removeFirst() }, { delivered.incrementAndGet() })
        try {
            assertTrue(capture.start(projection))
            await(oldReading)
            capture.stop()
            assertTrue(capture.start(projection))
            await(newReading)
            finishOld.countDown()
            oldThread!!.join(5000)
            assertFalse(oldThread!!.isAlive)
            assertTrue(capture.active)
            assertEquals(0, delivered.get())
            verify(first, times(1)).release()
        } finally {
            capture.stop()
            finishOld.countDown()
            finishNew.countDown()
        }
        verify(second, times(1)).release()
    }
}
