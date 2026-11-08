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
        val readerThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        `when`(record.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            readerThread.compareAndSet(null, Thread.currentThread())
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
        // Wait for the actual reader to leave its finally block; a read count
        // asserted immediately after releasing the pause can miss a late retry.
        val reader = readerThread.get()
        assertNotNull(reader)
        reader.join(5000)
        assertFalse("stopped audio reader must terminate", reader.isAlive)
        assertEquals(1, reads.get())
        assertEquals(0, delivered.get())
        assertFalse(capture.active)
        verify(record, times(1)).release()
    }


    @Test fun stopDuringBlockedPcmCallbackCompletesAfterCallbackReturns() {
        val record = mock(AudioRecord::class.java)
        val entered = CountDownLatch(1)
        val allowCallbackReturn = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val released = CountDownLatch(1)
        val delivered = AtomicInteger()
        val readerRef = java.util.concurrent.atomic.AtomicReference<Thread>()
        val reads = AtomicInteger()
        `when`(record.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            readerRef.set(Thread.currentThread())
            if (reads.incrementAndGet() > 1) {
                // A second mock read must not invent another audible PCM callback
                // before the stop thread acquires the ownership lock.
                assertTrue(released.await(5, TimeUnit.SECONDS))
                AudioRecord.ERROR_DEAD_OBJECT
            } else {
                val bytes = it.arguments[0] as ByteArray
                bytes[0] = 1
                bytes[1] = 0
                2
            }
        }
        doAnswer { released.countDown(); null }.`when`(record).release()
        val capture = AudioCapture({ record }, {
            delivered.incrementAndGet()
            entered.countDown()
            await(allowCallbackReturn)
        })
        val stopper = Thread {
            capture.stop()
            stopReturned.countDown()
        }
        try {
            assertTrue(capture.start(projection))
            await(entered)
            stopper.start()
            // Current synchronous callback contract: stop cannot finish while
            // onPcm owns the monitor. This guards lifecycle ordering, not latency.
            assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowCallbackReturn.countDown()
        }
        await(stopReturned)
        await(released)
        stopper.join(5000)
        val reader = readerRef.get()
        assertNotNull(reader)
        reader.join(5000)
        assertFalse("reader must terminate after stop", reader.isAlive)
        assertFalse(capture.active)
        assertEquals(1, delivered.get())
        verify(record, times(1)).release()
    }

    @Test fun blockedOldCallbackCannotDisableNewCaptureAfterReset() {
        val oldRecord = mock(AudioRecord::class.java)
        val newRecord = mock(AudioRecord::class.java)
        val callbackEntered = CountDownLatch(1)
        val finishCallback = CountDownLatch(1)
        val newReadEntered = CountDownLatch(1)
        val finishNewRead = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)
        val resetFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val oldReader = java.util.concurrent.atomic.AtomicReference<Thread>()
        val delivered = AtomicInteger()
        `when`(oldRecord.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            oldReader.set(Thread.currentThread())
            val bytes = it.arguments[0] as ByteArray
            bytes[0] = 1
            bytes[1] = 0
            2
        }
        `when`(newRecord.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            newReadEntered.countDown()
            await(finishNewRead)
            AudioRecord.ERROR_DEAD_OBJECT
        }
        val records = ArrayDeque(listOf(oldRecord, newRecord))
        val capture = AudioCapture({ records.removeFirst() }, {
            delivered.incrementAndGet()
            callbackEntered.countDown()
            await(finishCallback)
        })
        val reset = Thread {
            try {
                assertTrue(capture.start(projection))
            } catch (t: Throwable) {
                resetFailure.set(t)
            } finally {
                resetFinished.countDown()
            }
        }
        try {
            assertTrue(capture.start(projection))
            await(callbackEntered)
            reset.start()
            assertFalse("reset must wait for in-flight PCM callback", resetFinished.await(100, TimeUnit.MILLISECONDS))
            finishCallback.countDown()
            await(resetFinished)
            resetFailure.get()?.let { throw AssertionError("reset thread failed", it) }
            await(newReadEntered)
            oldReader.get().join(5000)
            assertFalse("old reader must exit", oldReader.get().isAlive)
            assertTrue("old reader must not clear replacement capture", capture.active)
            assertEquals("old PCM must not be duplicated after reset", 1, delivered.get())
            verify(oldRecord, times(1)).release()
        } finally {
            finishCallback.countDown()
            finishNewRead.countDown()
            capture.stop()
            reset.join(5000)
        }
        verify(newRecord, times(1)).release()
    }

    @Test fun nativeStopFailureStillReleasesRecorderAndAllowsRestart() {
        val first = mock(AudioRecord::class.java)
        val second = mock(AudioRecord::class.java)
        val firstReading = CountDownLatch(1)
        val finishFirstRead = CountDownLatch(1)
        val secondReading = CountDownLatch(1)
        val finishSecondRead = CountDownLatch(1)
        val delivered = AtomicInteger()
        `when`(first.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            firstReading.countDown()
            await(finishFirstRead)
            AudioRecord.ERROR_DEAD_OBJECT
        }
        `when`(second.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            secondReading.countDown()
            await(finishSecondRead)
            AudioRecord.ERROR_DEAD_OBJECT
        }
        doThrow(IllegalStateException("native recorder already stopped")).`when`(first).stop()
        val records = ArrayDeque(listOf(first, second))
        val capture = AudioCapture({ records.removeFirst() }, { delivered.incrementAndGet() })
        try {
            assertTrue(capture.start(projection))
            await(firstReading)
            // A native stop exception must not prevent release or poison restart.
            capture.stop()
            verify(first, times(1)).release()
            assertFalse(capture.active)
            assertTrue(capture.start(projection))
            await(secondReading)
            assertTrue(capture.active)
            assertEquals(0, delivered.get())
        } finally {
            capture.stop()
            finishFirstRead.countDown()
            finishSecondRead.countDown()
        }
        verify(first, times(1)).release()
        verify(second, times(1)).release()
    }

    @Test fun startRecordingFailureReleasesRecorderAndAllowsRetry() {
        val broken = mock(AudioRecord::class.java)
        val working = mock(AudioRecord::class.java)
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        doThrow(IllegalStateException("native start failed")).`when`(broken).startRecording()
        `when`(working.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            reading.countDown()
            await(finishRead)
            AudioRecord.ERROR_DEAD_OBJECT
        }
        val records = ArrayDeque(listOf(broken, working))
        val delivered = AtomicInteger()
        val capture = AudioCapture({ records.removeFirst() }, { delivered.incrementAndGet() })
        try {
            assertFalse("native start failure must be reported", capture.start(projection))
            assertFalse("failed recorder must not remain active", capture.active)
            verify(broken, times(1)).release()
            verify(broken, never()).read(any(ByteArray::class.java), anyInt(), anyInt())
            assertTrue("a failed start must not poison the next session", capture.start(projection))
            await(reading)
            assertTrue(capture.active)
            assertEquals(0, delivered.get())
        } finally {
            capture.stop()
            finishRead.countDown()
        }
        verify(broken, times(1)).release()
        verify(working, times(1)).release()
    }

    @Test fun callbackFailureReleasesRecorderAndAllowsNextSession() {
        val broken = mock(AudioRecord::class.java)
        val working = mock(AudioRecord::class.java)
        val brokenReleased = CountDownLatch(1)
        val workingReading = CountDownLatch(1)
        val finishWorkingRead = CountDownLatch(1)
        val deliveries = AtomicInteger()
        doAnswer { brokenReleased.countDown(); null }.`when`(broken).release()
        `when`(broken.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            val bytes = it.arguments[0] as ByteArray
            bytes[0] = 1
            bytes[1] = 0
            2
        }
        `when`(working.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            workingReading.countDown()
            await(finishWorkingRead)
            AudioRecord.ERROR_DEAD_OBJECT
        }
        val records = ArrayDeque(listOf(broken, working))
        val capture = AudioCapture({ records.removeFirst() }, {
            if (deliveries.incrementAndGet() == 1) throw IllegalStateException("PCM consumer failed")
        })
        try {
            assertTrue(capture.start(projection))
            await(brokenReleased)
            assertFalse("failed PCM consumer must clear active capture", capture.active)
            verify(broken, times(1)).release()
            assertTrue("consumer failure must not poison the next session", capture.start(projection))
            await(workingReading)
            assertTrue(capture.active)
            assertEquals(1, deliveries.get())
        } finally {
            capture.stop()
            finishWorkingRead.countDown()
        }
        verify(broken, times(1)).release()
        verify(working, times(1)).release()
    }

    @Test fun callbackInitiatedStopDoesNotDeliverLaterPcm() {
        val record = mock(AudioRecord::class.java)
        val reader = java.util.concurrent.atomic.AtomicReference<Thread>()
        val readerFinished = CountDownLatch(1)
        val delivered = AtomicInteger()
        val readCount = AtomicInteger()
        `when`(record.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            reader.set(Thread.currentThread())
            readCount.incrementAndGet()
            val bytes = it.arguments[0] as ByteArray
            bytes[0] = 1
            bytes[1] = 0
            2
        }
        lateinit var capture: AudioCapture
        capture = AudioCapture({ record }, {
            delivered.incrementAndGet()
            // stop() may be invoked synchronously by a PCM consumer.
            // It must not deadlock or deliver another block after returning.
            capture.stop()
            readerFinished.countDown()
        })
        assertTrue(capture.start(projection))
        await(readerFinished)
        reader.get().join(5000)
        assertFalse("reader must exit after callback-initiated stop", reader.get().isAlive)
        assertEquals(1, delivered.get())
        assertEquals(1, readCount.get())
        assertFalse(capture.active)
        verify(record, times(1)).release()
    }

    @Test fun concurrentStopAndThrowingCallbackReleaseOnceWithoutLatePcm() {
        val record = mock(AudioRecord::class.java)
        val callbackEntered = CountDownLatch(1)
        val finishCallback = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val released = CountDownLatch(1)
        val readerRef = java.util.concurrent.atomic.AtomicReference<Thread>()
        val stopFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val reads = AtomicInteger()
        val deliveries = AtomicInteger()
        `when`(record.read(any(ByteArray::class.java), anyInt(), anyInt())).thenAnswer {
            readerRef.set(Thread.currentThread())
            reads.incrementAndGet()
            val bytes = it.arguments[0] as ByteArray
            bytes[0] = 1
            bytes[1] = 0
            2
        }
        doAnswer { released.countDown(); null }.`when`(record).release()
        val capture = AudioCapture({ record }, {
            deliveries.incrementAndGet()
            callbackEntered.countDown()
            await(finishCallback)
            throw IllegalStateException("consumer failed during stop")
        })
        val stopper = Thread {
            try {
                capture.stop()
            } catch (t: Throwable) {
                stopFailure.set(t)
            } finally {
                stopReturned.countDown()
            }
        }
        try {
            assertTrue(capture.start(projection))
            await(callbackEntered)
            stopper.start()
            assertFalse("stop waits for in-flight callback", stopReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            finishCallback.countDown()
        }
        await(stopReturned)
        await(released)
        stopper.join(5000)
        val reader = readerRef.get()
        assertNotNull(reader)
        reader.join(5000)
        assertFalse("failed callback reader must terminate", reader.isAlive)
        stopFailure.get()?.let { throw AssertionError("stop failed", it) }
        assertFalse(capture.active)
        assertEquals(1, reads.get())
        assertEquals(1, deliveries.get())
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
