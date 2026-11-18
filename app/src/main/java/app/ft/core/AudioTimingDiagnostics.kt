package app.ft.core

/**
 * Quantitative, content-free audio timing audit. No PCM, artist, application,
 * device identity, timestamps of private activity or phone content are retained.
 * All clocks are monotonic. Each instance belongs to one live session/lane.
 */
internal class AudioIngressTiming(
    private val gapThresholdMs: Long = 80L
) {
    private var previousArrivalNs = -1L
    private var previousDurationNs = 0L
    private var lastReportNs = -1L
    private var packets = 0L
    private var gaps = 0L
    private var bursts = 0L
    private var maxGapMs = 0L
    private var maxIntervalMs = 0L

    /** Packet arrival + expected previous PCM duration, not decoded-audio playout. */
    @Synchronized
    fun onPcm(nowNs: Long, bytes: Int, sampleRate: Int, channels: Int): String? {
        if (bytes <= 0 || sampleRate <= 0 || channels <= 0) return null
        val durationNs = bytes.toLong() * 1_000_000_000L / (sampleRate * channels * 2L)
        var lateMs = 0L
        if (previousArrivalNs >= 0L && nowNs >= previousArrivalNs) {
            val intervalNs = nowNs - previousArrivalNs
            maxIntervalMs = maxOf(maxIntervalMs, intervalNs / 1_000_000L)
            lateMs = (intervalNs - previousDurationNs).coerceAtLeast(0L) / 1_000_000L
            if (lateMs >= gapThresholdMs) {
                gaps++
                maxGapMs = maxOf(maxGapMs, lateMs)
            }
            if (intervalNs <= 5_000_000L) bursts++
        }
        previousArrivalNs = nowNs
        previousDurationNs = durationNs
        packets++
        if (lastReportNs < 0L) { lastReportNs = nowNs; return null }
        val ageNs = nowNs - lastReportNs
        if (ageNs < 0L) { lastReportNs = nowNs; return null }
        if (ageNs < 5_000_000_000L && !(lateMs >= 250L && ageNs >= 1_000_000_000L))
            return null
        lastReportNs = nowNs
        return summary()
    }

    /** A normal AV_STOP/AV_START must not be classified as an audio glitch. */
    @Synchronized fun streamBoundary() {
        previousArrivalNs = -1L
        previousDurationNs = 0L
    }

    @Synchronized
    fun summary(): String = "packets=$packets ingressGaps=$gaps " +
        "maxUnexpectedGapMs=$maxGapMs maxIntervalMs=$maxIntervalMs bursts=$bursts"

    @Synchronized fun gapCount(): Long = gaps
    @Synchronized fun largestGapMs(): Long = maxGapMs
}

/**
 * AAC-side evidence, kept separate from CarAudioBus 'dropped' which only
 * accounts for the mixer/hold buffer. Output PTS is relative audio timing.
 */
internal class AacOutputTiming {
    private var pcmEvictedBlocks = 0L
    private var pcmEvictedBytes = 0L
    private var highWaterDepth = 0
    private var aacFrames = 0L
    private var ptsGaps = 0L
    private var ptsBackwards = 0L
    private var maxPtsGapMs = 0L
    private var lastPtsUs = -1L

    @Synchronized fun queued(depth: Int) {
        highWaterDepth = maxOf(highWaterDepth, depth.coerceAtLeast(0))
    }

    @Synchronized fun evictedPcm(bytes: Int) {
        if (bytes > 0) {
            pcmEvictedBlocks++
            pcmEvictedBytes += bytes
        }
    }

    /** AAC-LC at 48kHz normally advances by 1024 samples ~= 21.33ms. */
    @Synchronized fun aacOutput(ptsUs: Long) {
        if (lastPtsUs >= 0L) {
            val delta = ptsUs - lastPtsUs
            if (delta <= 0L) ptsBackwards++
            else {
                val missingUs = delta - (1024L * 1_000_000L / 48000L)
                if (missingUs >= 50_000L) {
                    ptsGaps++
                    maxPtsGapMs = maxOf(maxPtsGapMs, missingUs / 1000L)
                }
            }
        }
        lastPtsUs = ptsUs
        aacFrames++
    }

    @Synchronized fun summary(currentDepth: Int): String =
        "pcmPending=$currentDepth pcmPeak=$highWaterDepth " +
        "pcmEvictedBlocks=$pcmEvictedBlocks pcmEvictedBytes=$pcmEvictedBytes " +
        "aacFrames=$aacFrames aacPtsGaps=$ptsGaps " +
        "aacPtsBackwards=$ptsBackwards maxAacPtsGapMs=$maxPtsGapMs"

    @Synchronized fun evictedBlocks(): Long = pcmEvictedBlocks
    @Synchronized fun ptsGapCount(): Long = ptsGaps
}
