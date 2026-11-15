package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaStreamQueueTest {
    private val bytes = byteArrayOf(1, 2, 3)

    @Test fun waitingWriterWakesForNewMediaAndCanBeInterruptedOnClose() {
        val queue = IccoaStreamQueue()
        queue.resume(0)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val waiting = java.util.concurrent.CountDownLatch(1)
            val result = executor.submit<IccoaStreamQueue.Frame?> {
                waiting.countDown()
                queue.poll(10_000)
            }
            assertTrue(waiting.await(1, java.util.concurrent.TimeUnit.SECONDS))
            queue.video(bytes, true, 1)
            assertEquals(1L, result.get(1, java.util.concurrent.TimeUnit.SECONDS)!!.pts)
            assertNull(queue.poll(1))
            val blocking = java.util.concurrent.CountDownLatch(1)
            val blocked = executor.submit<IccoaStreamQueue.Frame?> {
                blocking.countDown()
                queue.poll(10_000)
            }
            assertTrue(blocking.await(1, java.util.concurrent.TimeUnit.SECONDS))
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(blocked.isDone)
        } finally { executor.shutdownNow() }
    }

    @Test fun resumeDropsStaleMediaAndWaitsForNewKeyframe() {
        val queue = IccoaStreamQueue()
        queue.video(bytes, true, 1)
        assertNull(queue.poll())
        queue.resume(100)
        queue.video(bytes, true, 99)
        queue.video(bytes, false, 100)
        queue.audio(bytes, 100, queue.audioEpoch()!!)
        assertNull(queue.poll())
        queue.video(bytes, true, 101)
        val epoch = queue.audioEpoch()!!
        queue.audio(bytes, 102, epoch)
        val previous = queue.poll()!!
        assertTrue(previous.video)
        queue.pause()
        assertFalse(queue.current(previous))
        assertNull(queue.audioEpoch())
        queue.resume(200)
        queue.video(bytes, true, 201)
        // Even an encoder frame with a future timestamp belongs to the old paused epoch.
        queue.audio(bytes, 999, epoch)
        queue.audio(bytes, 202, queue.audioEpoch()!!)
        assertTrue(queue.poll()!!.video)
        assertEquals(202L, queue.poll()!!.pts)
        assertNull(queue.poll())
    }

    @Test fun videoOverflowDropsBrokenGopAndRequestsIdr() {
        val queue = IccoaStreamQueue()
        queue.resume(0)
        repeat(8) { assertFalse(queue.video(bytes, it == 0, it.toLong())) }
        queue.audio(bytes, 8, queue.audioEpoch()!!)
        assertTrue(queue.video(bytes, false, 9))
        queue.audio(bytes, 10, queue.audioEpoch()!!)
        assertNull(queue.poll())
        assertFalse(queue.video(bytes, true, 11))
        assertTrue(queue.poll()!!.key)
    }

    @Test fun boundedAudioKeepsVideoAndMostRecentSound() {
        val queue = IccoaStreamQueue()
        queue.resume(0)
        queue.video(bytes, true, 0)
        repeat(40) { queue.audio(bytes, it + 1L, queue.audioEpoch()!!) }
        assertTrue(queue.poll()!!.video)
        val sound = generateSequence { queue.poll() }.toList()
        assertEquals(32, sound.size)
        assertEquals(9L, sound.first().pts)
        assertEquals(40L, sound.last().pts)
    }

    @Test fun drainedAndPausedQueuesCanBeRefilledToTheirOriginalLimits() {
        val queue = IccoaStreamQueue()
        queue.resume(0)
        repeat(3) { round ->
            repeat(8) { assertFalse(queue.video(bytes, it == 0, it.toLong())) }
            repeat(32) { queue.audio(bytes, it.toLong(), queue.audioEpoch()!!) }
            if (round == 1) {
                queue.pause()
                queue.resume(0)
            } else {
                repeat(40) { index ->
                    val frame = if (round == 0) queue.poll() else queue.poll(10)
                    assertNotNull(frame)
                    assertEquals(index < 8, frame!!.video)
                }
                assertNull(queue.poll())
            }
        }
    }

    @Test fun directIdrIsTaggedAndStaleCompositedFramesCanBeDistinguished() {
        val queue = IccoaStreamQueue()
        queue.resume(0)
        queue.video(bytes, true, 1)
        queue.pause()
        queue.resume(100)
        queue.video(byteArrayOf(0, 0, 0, 1, 0x65), true, 101, direct = true)
        val key = queue.poll()!!
        assertTrue(key.key)
        assertTrue(key.direct)
        assertTrue(queue.current(key))
        queue.pause()
        assertFalse(queue.current(key))
    }

    @Test fun staleInFlightDirectIdrCannotCertifyPostPauseRtpRecovery() {
        val queue = IccoaStreamQueue()
        val idr = byteArrayOf(0, 0, 0, 1, 0x65)
        assertTrue(queue.resume(100))
        queue.video(idr, true, 101, direct = true)
        // Sender has already polled this IDR; its socket write is still pending.
        val inFlightBeforePause = queue.poll()!!
        assertTrue(queue.isCurrentDirectIdr(inFlightBeforePause))
        queue.pause()
        assertTrue(queue.resume(200))
        // Even when the old write completes after PLAY, it is not the new GOP.
        assertFalse(queue.isCurrentDirectIdr(inFlightBeforePause))
        queue.video(idr, true, 201, direct = true)
        val postPause = queue.poll()!!
        assertTrue(queue.isCurrentDirectIdr(postPause))
        queue.pause()
        assertFalse(queue.isCurrentDirectIdr(postPause))
    }

    @Test fun retainedFirstIdrHandoffSeedsTheNewEpochBeforePFrames() {
        val queue = IccoaStreamQueue()
        val idr = byteArrayOf(0, 0, 0, 1, 0x65)
        queue.resume(100)
        queue.video(bytes, true, 101)
        queue.pause()
        queue.resume(200)
        // During the Direct cutover a P frame cannot open a new GOP.
        assertFalse(queue.video(bytes, false, 200))
        assertNull(queue.poll())
        assertFalse(queue.video(idr, true, 201))
        assertFalse(queue.video(bytes, false, 202))
        assertTrue(queue.poll()!!.key)
        assertFalse(queue.poll()!!.key)
    }

    @Test fun duplicatePlayDoesNotDiscardQueuedMediaOrRequireAnotherIdr() {
        val queue = IccoaStreamQueue()
        assertTrue(queue.resume(100))
        queue.video(bytes, true, 101)
        assertFalse(queue.resume(200))
        queue.video(bytes, false, 102)
        assertEquals(101L, queue.poll()!!.pts)
        assertEquals(102L, queue.poll()!!.pts)
    }

    @Test fun directAfterOemPauseResumesOnlyAtANewKeyframe() {
        val queue = IccoaStreamQueue()
        assertTrue(queue.resume(100))
        val idr = byteArrayOf(0, 0, 0, 1, 0x65)
        val pFrame = byteArrayOf(0, 0, 0, 1, 0x61)
        queue.video(idr, true, 101, direct = true)
        val previouslyQueued = queue.poll()!!
        queue.pause()
        assertFalse(queue.current(previouslyQueued))
        assertTrue(queue.resume(200))
        assertFalse(queue.video(pFrame, false, 201, direct = true))
        assertNull(queue.poll())
        assertFalse(queue.video(idr, true, 202, direct = true))
        assertFalse(queue.video(pFrame, false, 203, direct = true))
        assertTrue(queue.poll()!!.key)
        assertFalse(queue.poll()!!.key)
        assertFalse(queue.resume(250))
    }

    @Test fun pcmClockDoesNotRewindForBurstOrOldCaptureTimes() {
        val clock = IccoaPcmClock()
        repeat(8) { chunk ->
            val expected = 1_000_000L + chunk * 2048L * 1_000_000L / 48000
            assertEquals(expected, clock.take(8192, 1_000_000))
        }
        assertEquals(1_000_000L + 16384L * 1_000_000L / 48000, clock.take(8192, 0))
    }

    @Test fun pcmClockPreservesSamplePrecisionAndRebasesAfterPause() {
        val clock = IccoaPcmClock()
        repeat(48000) { sample ->
            val expected = 1_000_000L + sample * 1_000_000L / 48000
            assertEquals(expected, clock.take(4, expected))
        }
        assertEquals(3_000_000L, clock.take(4096, 3_000_000))
        assertThrows(IllegalArgumentException::class.java) { clock.take(3, 0) }
    }
}
