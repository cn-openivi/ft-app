package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class AudioTimingDiagnosticsTest {
    private val pcmBytes = 8192 // 48kHz stereo PCM16: 42.67ms

    @Test fun normalPacketsDoNotCreateGaps() {
        val meter = AudioIngressTiming()
        repeat(20) { i -> meter.onPcm(100_000_000L + i * 43_000_000L, pcmBytes, 48000, 2) }
        assertEquals(0L, meter.gapCount())
    }

    @Test fun coldOpenDelayIsMeasuredWithoutSavingAudio() {
        val meter = AudioIngressTiming()
        assertNull(meter.onPcm(100_000_000L, pcmBytes, 48000, 2))
        val line = meter.onPcm(1_600_000_000L, pcmBytes, 48000, 2)
        assertNotNull(line)
        assertTrue(line!!.contains("ingressGaps=1"))
        assertTrue(meter.largestGapMs() in 1450L..1460L)
        assertFalse(line.contains("PCM16"))
    }

    @Test fun normalStopAndRestartDoesNotClaimStutter() {
        val meter = AudioIngressTiming()
        meter.onPcm(100_000_000L, pcmBytes, 48000, 2)
        meter.streamBoundary()
        meter.onPcm(9_000_000_000L, pcmBytes, 48000, 2)
        assertEquals(0L, meter.gapCount())
    }

    @Test fun burstIsDistinctFromMissingTime() {
        val meter = AudioIngressTiming()
        meter.onPcm(100_000_000L, pcmBytes, 48000, 2)
        val r = meter.onPcm(101_000_000L, pcmBytes, 48000, 2)
        assertNull(r)
        assertTrue(meter.summary().contains("bursts=1"))
    }

    @Test fun pcmQueueEvictionsAreNotSilentlyLostInStatistics() {
        val meter = AacOutputTiming()
        meter.queued(5); meter.queued(8); meter.queued(3)
        meter.evictedPcm(8192); meter.evictedPcm(4096)
        assertEquals(2L, meter.evictedBlocks())
        assertTrue(meter.summary(3).contains("pcmPeak=8"))
        assertTrue(meter.summary(3).contains("pcmEvictedBytes=12288"))
    }

    @Test fun regularAacPtsHaveNoFalseGaps() {
        val meter = AacOutputTiming()
        repeat(20) { meter.aacOutput(it * 21333L) }
        assertEquals(0L, meter.ptsGapCount())
    }

    @Test fun trueAacPtsDiscontinuityAndRegressionAreCounted() {
        val meter = AacOutputTiming()
        meter.aacOutput(100_000L)
        meter.aacOutput(121_333L)
        meter.aacOutput(300_000L)
        meter.aacOutput(290_000L)
        assertEquals(1L, meter.ptsGapCount())
        assertTrue(meter.summary(0).contains("aacPtsBackwards=1"))
        assertTrue(meter.summary(0).contains("maxAacPtsGapMs=157"))
    }
}
