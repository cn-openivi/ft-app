package app.ft.dr

import java.util.ArrayDeque
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * GNSS observation-admission step of the SAME TinyEKF, not a second filter.
 *
 * Inspired by FusionCore's five-fix continuity tests (Apache-2.0), written
 * independently for FT. A small 10-20m reflected GPS fix can fit a receiver's
 * large covariance and pass an EKF NIS test; its local trajectory continuity
 * still differs from recent accepted GPS and verified wheel/gyro prediction.
 *
 * Actual GNSS timestamps, not callback/arrival times, are required. On a
 * real blackout the short-term history is discarded, but TinyEKF's predicted
 * position, heading and odometry covariance are deliberately NOT reset.
 * No forced reanchor after N rejected fixes. No spoof-detection claim.
 */
internal class GnssContinuityGate {
    private data class Fix(val timestampMs: Long, val east: Double, val north: Double)
    private val accepted = ArrayDeque<Fix>(5)
    private val cleanResiduals = ArrayDeque<Double>(5)
    private var lastObservedTimestampMs = Long.MIN_VALUE

    fun reset() {
        accepted.clear()
        cleanResiduals.clear()
        lastObservedTimestampMs = Long.MIN_VALUE
    }

    /**
     * Returns only a GNSS admission decision, never a navigation estimate.
     * Candidate acceptance MUST be followed by confirmed() only after a
     * successful TinyEKF update.
     */
    fun permits(
        timestampMs: Long,
        east: Double,
        north: Double,
        predictedEast: Double,
        predictedNorth: Double
    ): Boolean {
        if (timestampMs <= 0L || !east.isFinite() || !north.isFinite() ||
            !predictedEast.isFinite() || !predictedNorth.isFinite()) return false
        // Use the stream of ALL GNSS candidates, including rejected ones,
        // for gap detection and out-of-order protection. A stable reflection
        // series cannot re-anchor simply because none was accepted recently.
        // Rejected observations can inform gap detection, but cannot set
        // the ordering floor for a later genuinely accepted GNSS fix.
        // Only the last confirmed fix defines that floor (checked below).
        val actualGap = lastObservedTimestampMs != Long.MIN_VALUE &&
            timestampMs - lastObservedTimestampMs > GPS_GAP_MS
        // Do not commit a candidate clock before EKF acceptance.
        val last = accepted.peekLast() ?: return true
        if (timestampMs <= last.timestampMs) return false
        if (actualGap) return true
        if (accepted.size < HISTORY_REQUIRED) return true

        val projected = forecast(timestampMs) ?: return true
        val lineResidual = hypot(east - projected.first, north - projected.second)
        // During turns a five-point straight-line trend can be wrong.
        // Verified wheel/yaw TinyEKF prediction is another independent
        // trajectory; use whichever trajectory fits better, not GPS accuracy.
        val wheelResidual = hypot(east - predictedEast, north - predictedNorth)
        val continuityResidual = min(lineResidual, wheelResidual)
        val learned = cleanResiduals.maxOrNull() ?: 0.0
        val tolerance = max(BASE_TOLERANCE_METRES, learned * 2.5)
            .coerceAtMost(MAX_TOLERANCE_METRES)
        val allowed = continuityResidual.isFinite() && continuityResidual <= tolerance
        // Contiguous rejected reflections still count as observations, but
        // speculative far-future candidates cannot poison the time anchor.
        if (!allowed && !actualGap && timestampMs > lastObservedTimestampMs) {
            lastObservedTimestampMs = timestampMs
        }
        return allowed
    }

    fun confirmed(
        timestampMs: Long,
        east: Double,
        north: Double,
        predictedEast: Double,
        predictedNorth: Double
    ) {
        // Only a genuine gap in observed GNSS packets clears the local
        // continuity history. Repeated rejected measurements must never
        // reset it or provide a false re-anchor.
        if (lastObservedTimestampMs != Long.MIN_VALUE &&
            timestampMs > lastObservedTimestampMs &&
            timestampMs - lastObservedTimestampMs > GPS_GAP_MS) {
            accepted.clear()
            cleanResiduals.clear()
            }
        if (timestampMs > lastObservedTimestampMs) lastObservedTimestampMs = timestampMs
        val line = if (accepted.size >= HISTORY_REQUIRED)
            forecast(timestampMs) else null
        if (line != null) {
            val clean = min(
                hypot(east - line.first, north - line.second),
                hypot(east - predictedEast, north - predictedNorth)
            )
            // Do not train a larger threshold from a huge GPS outlier.
            if (clean.isFinite() && clean <= BASE_TOLERANCE_METRES) {
                if (cleanResiduals.size == HISTORY_REQUIRED) cleanResiduals.removeFirst()
                cleanResiduals.addLast(clean)
            }
        }
        if (accepted.size == HISTORY_REQUIRED) accepted.removeFirst()
        accepted.addLast(Fix(timestampMs, east, north))
    }

    private fun forecast(timestampMs: Long): Pair<Double, Double>? {
        val n = accepted.size
        if (n < HISTORY_REQUIRED) return null
        var sumT = 0.0
        var sumTT = 0.0
        var sumE = 0.0
        var sumN = 0.0
        var sumTE = 0.0
        var sumTN = 0.0
        for (fix in accepted) {
            val t = (fix.timestampMs - timestampMs) / 1000.0
            sumT += t
            sumTT += t * t
            sumE += fix.east
            sumN += fix.north
            sumTE += t * fix.east
            sumTN += t * fix.north
        }
        val determinant = n * sumTT - sumT * sumT
        if (determinant <= 1e-6 || !determinant.isFinite()) return null
        val slopeE = (n * sumTE - sumT * sumE) / determinant
        val slopeN = (n * sumTN - sumT * sumN) / determinant
        val resultE = (sumE - slopeE * sumT) / n
        val resultN = (sumN - slopeN * sumT) / n
        return if (resultE.isFinite() && resultN.isFinite())
            resultE to resultN else null
    }

    private companion object {
        const val HISTORY_REQUIRED = 5
        const val GPS_GAP_MS = 5_000L
        const val BASE_TOLERANCE_METRES = 4.0
        const val MAX_TOLERANCE_METRES = 15.0
    }
}
