package app.ft.aa

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AaVideoDecoderTest {
    @Test fun slowDecoderInputCannotWaitTwentyMillisecondsOnAaDispatchThread() {
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(-1)
        `when`(codec.dequeueOutputBuffer(
            any(MediaCodec.BufferInfo::class.java), anyLong()
        )).thenAnswer {
            Thread.sleep(2)
            MediaCodec.INFO_TRY_AGAIN_LATER
        }
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }.thenReturn(codec)
                val decoder = AaVideoDecoder(1280, 720)
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(0, 0, 1, 103))
                    decoder.onFrame(byteArrayOf(0, 0, 1, 65, 7), 1_000L)
                    // Exact MediaCodec API argument: 2000 MICROseconds,
                    // rather than the prior blocking 20_000us.
                    verify(codec).dequeueInputBuffer(AaVideoDecoder.INPUT_DEQUEUE_TIMEOUT_US)
                    assertEquals(2_000L, AaVideoDecoder.INPUT_DEQUEUE_TIMEOUT_US)
                    assertTrue(AaVideoDecoder.INPUT_DEQUEUE_TIMEOUT_US < 16_667L)
                } finally {
                    decoder.reset()
                }
            }
        }
    }

    @Test fun recycledInputBufferWithNonzeroPositionStillAcceptsFrameWithinCapacity() {
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        val input = java.nio.ByteBuffer.allocate(16)
        // The frame fits the physical slot but not its inherited remaining().
        input.position(14)
        input.limit(15)
        `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(0)
        `when`(codec.getInputBuffer(0)).thenReturn(input)
        `when`(codec.dequeueOutputBuffer(
            any(MediaCodec.BufferInfo::class.java), anyLong()
        )).thenAnswer {
            Thread.sleep(2)
            MediaCodec.INFO_TRY_AGAIN_LATER
        }
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }.thenReturn(codec)
                val decoder = AaVideoDecoder(1280, 720)
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(0, 0, 1, 103))
                    val frame = byteArrayOf(0, 0, 1, 65, 7)
                    decoder.onFrame(frame, 1234L)
                    verify(codec).queueInputBuffer(0, 0, frame.size, 1234L, 0)
                    assertArrayEquals(frame, input.array().copyOfRange(0, frame.size))
                } finally {
                    decoder.reset()
                }
            }
        }
    }

    @Test fun lateFormatFromClosedCodecCannotOverwriteNewSessionSizeOrRestartFromOldConfig() {
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val drainThread = AtomicReference<Thread>()
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong())).thenAnswer {
            drainThread.set(Thread.currentThread())
            entered.countDown()
            check(leave.await(5, TimeUnit.SECONDS))
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
        }
        `when`(codec.outputFormat).thenReturn(format)
        `when`(format.getInteger(MediaFormat.KEY_WIDTH)).thenReturn(800)
        `when`(format.getInteger(MediaFormat.KEY_HEIGHT)).thenReturn(480)
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> { MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 720) }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.thenReturn(codec)
                val decoder = AaVideoDecoder(1920, 720)
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(1, 2, 3))
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    decoder.reset()
                    AaVideoSink.frameSize(1920, 720)
                    leave.countDown()
                    drainThread.get().join(5000)
                    assertFalse(drainThread.get().isAlive)
                    assertEquals(1920 to 720, AaVideoSink.frame.value)
                    verify(codec, never()).getOutputFormat()
                    decoder.setSurface(mock(Surface::class.java))
                    codecs.verify({ MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }, times(1))
                    assertNull(decoder.askForAFreshPicture)
                } finally {
                    leave.countDown()
                    drainThread.get()?.join(5000)
                    decoder.reset()
                }
            }
        }
    }

    @Test fun stopMustNotReleaseCodecWhileOutputBufferIsBeingRendered() {
        val releaseEntered = CountDownLatch(1)
        val resetEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        val releaseCount = java.util.concurrent.atomic.AtomicInteger(0)
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong())).thenReturn(0, MediaCodec.INFO_TRY_AGAIN_LATER)
        doAnswer {
            releaseEntered.countDown()
            check(allowRelease.await(5, TimeUnit.SECONDS))
            releaseCount.incrementAndGet()
            null
        }.`when`(codec).releaseOutputBuffer(0, true)
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }.thenReturn(codec)
                val decoder = AaVideoDecoder(1280, 720)
                var stopper: Thread? = null
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(1, 2, 3))
                    assertTrue("drain did not reach releaseOutputBuffer", releaseEntered.await(5, TimeUnit.SECONDS))
                    stopper = Thread {
                        resetEntered.countDown()
                        decoder.reset()
                    }
                    stopper.start()
                    assertTrue("reset thread did not start", resetEntered.await(5, TimeUnit.SECONDS))
                    // Test the lifecycle contract rather than Thread.State.BLOCKED:
                    // an implementation may safely await in WAITING/TIMED_WAITING.
                    stopper.join(50)
                    assertTrue("reset returned before output rendering completed", stopper.isAlive)
                    // The codec must not be stopped/released while a render operation owns its buffer.
                    verify(codec, never()).stop()
                    verify(codec, never()).release()
                } finally {
                    allowRelease.countDown()
                    stopper?.join(5000)
                    decoder.reset()
                }
                assertFalse("reset remained blocked", stopper?.isAlive ?: true)
                assertEquals(1, releaseCount.get())
                verify(codec, times(1)).stop()
                verify(codec, times(1)).release()
            }
        }
    }
    @Test fun onFrameMustNotWaitForSlowOutputRendering() {
        val rendering = CountDownLatch(1)
        val finishRender = CountDownLatch(1)
        val inputFinished = CountDownLatch(1)
        val inputFailure = AtomicReference<Throwable?>()
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(-1)
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong()))
            .thenReturn(0, MediaCodec.INFO_TRY_AGAIN_LATER)
        doAnswer {
            rendering.countDown()
            check(finishRender.await(5, TimeUnit.SECONDS))
            null
        }.`when`(codec).releaseOutputBuffer(0, true)
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }.thenReturn(codec)
                val decoder = AaVideoDecoder(1280, 720)
                val input = Thread {
                    try {
                        decoder.onFrame(byteArrayOf(0, 0, 1, 65), 42L)
                    } catch (failure: Throwable) {
                        inputFailure.set(failure)
                    } finally {
                        inputFinished.countDown()
                    }
                }
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(1, 2, 3))
                    assertTrue("output render never started", rendering.await(5, TimeUnit.SECONDS))
                    input.start()
                    // Render remains blocked by the fixture; onFrame must finish
                    // independently, without waiting for releaseOutputBuffer.
                    assertTrue("AA input dispatch blocked behind output render",
                        inputFinished.await(1, TimeUnit.SECONDS))
                    assertNull("AA input thread failed while render was pending", inputFailure.get())
                    verify(codec).dequeueInputBuffer(AaVideoDecoder.INPUT_DEQUEUE_TIMEOUT_US)
                } finally {
                    finishRender.countDown()
                    input.join(5000)
                    decoder.reset()
                }
            }
        }
    }


    @Test fun resetWaitingForOutputMustNotHoldAaInputDispatchLock() {
        val rendering = CountDownLatch(1)
        val allowRender = CountDownLatch(1)
        val inputFinished = CountDownLatch(1)
        val inputFailure = AtomicReference<Throwable?>()
        val codec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(-1)
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong()))
            .thenReturn(0, MediaCodec.INFO_TRY_AGAIN_LATER)
        doAnswer {
            rendering.countDown()
            check(allowRender.await(5, TimeUnit.SECONDS))
            null
        }.`when`(codec).releaseOutputBuffer(0, true)
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }.thenReturn(codec)
                val decoder = AaVideoDecoder(1280, 720)
                val resetThread = Thread { decoder.reset() }
                val inputThread = Thread {
                    try {
                        decoder.onFrame(byteArrayOf(0, 0, 1, 65), 7L)
                    } catch (t: Throwable) {
                        inputFailure.set(t)
                    } finally {
                        inputFinished.countDown()
                    }
                }
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(1, 2, 3))
                    assertTrue("render did not begin", rendering.await(5, TimeUnit.SECONDS))
                    resetThread.start()
                    // Volatile codec=null is published by stopLocked immediately before
                    // it waits for outputLock. Observe the lifecycle milestone, not
                    // Thread.State or an arbitrary sleep-based scheduling assumption.
                    val field = AaVideoDecoder::class.java.getDeclaredField("codec")
                    field.isAccessible = true
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (field.get(decoder) != null && System.nanoTime() < deadline) {
                        Thread.yield()
                    }
                    assertNull("reset never invalidated the codec", field.get(decoder))
                    verify(codec, never()).stop()
                    verify(codec, never()).release()
                    inputThread.start()
                    assertTrue("onFrame blocked behind reset waiting for output render",
                        inputFinished.await(1, TimeUnit.SECONDS))
                    assertNull("onFrame threw", inputFailure.get())
                    verify(codec, never()).stop()
                    verify(codec, never()).release()
                } finally {
                    allowRender.countDown()
                    resetThread.join(5000)
                    if (inputThread.state != Thread.State.NEW) inputThread.join(5000)
                    decoder.reset()
                }
                assertFalse("reset did not finish", resetThread.isAlive)
                verify(codec, times(1)).stop()
                verify(codec, times(1)).release()
            }
        }
    }

    @Test fun failedDrainCodecStopMustNotBlockNextAaFrame() {
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        val nextFrameFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val oldCodec = mock(MediaCodec::class.java)
        val nextCodec = mock(MediaCodec::class.java)
        val format = mock(MediaFormat::class.java)
        `when`(oldCodec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong()))
            .thenThrow(IllegalStateException("decoder failed"))
        `when`(nextCodec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong()))
            .thenAnswer { Thread.sleep(2); MediaCodec.INFO_TRY_AGAIN_LATER }
        `when`(nextCodec.dequeueInputBuffer(anyLong())).thenReturn(-1)
        doAnswer {
            stopEntered.countDown()
            check(allowStop.await(5, TimeUnit.SECONDS))
            null
        }.`when`(oldCodec).stop()
        mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> {
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            }.thenReturn(format)
            mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> {
                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }.thenReturn(oldCodec, nextCodec)
                val decoder = AaVideoDecoder(1280, 720)
                val input = Thread {
                    try {
                        // Mockito static mocks are thread-local. The initial codec mock
                        // belongs to the test thread, but recovery runs on this input
                        // thread and must install its own factory fixture.
                        mockStatic(MediaFormat::class.java).use { inputFormats ->
                            inputFormats.`when`<MediaFormat> {
                                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
                            }.thenReturn(format)
                            mockStatic(MediaCodec::class.java).use { inputCodecs ->
                                inputCodecs.`when`<MediaCodec> {
                                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                                }.thenReturn(nextCodec)
                                decoder.onFrame(byteArrayOf(0, 0, 1, 65), 1000L)
                                // A swallowed startLocked failure must not masquerade
                                // as successful recovery just because onFrame returned.
                                verify(nextCodec, times(1)).start()
                            }
                        }
                    } catch (t: Throwable) {
                        failure.set(t)
                    } finally {
                        nextFrameFinished.countDown()
                    }
                }
                try {
                    decoder.setSurface(mock(Surface::class.java))
                    decoder.onConfig(byteArrayOf(1, 2, 3))
                    assertTrue("failed drain did not reach codec.stop", stopEntered.await(5, TimeUnit.SECONDS))
                    input.start()
                    assertTrue("AA frame blocked by failed decoder cleanup",
                        nextFrameFinished.await(1, TimeUnit.SECONDS))
                    assertNull(failure.get())
                } finally {
                    allowStop.countDown()
                    if (input.state != Thread.State.NEW) input.join(5000)
                    decoder.reset()
                }
                verify(oldCodec, times(1)).release()
                verify(nextCodec, times(1)).release()
            }
        }
    }

}
