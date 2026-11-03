package app.ft.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Read-only, source-independent automatic wheel-speed calibration for future DR.
 *
 * Never modifies the FT speedometer widget, ICCOA/Baidu source data or GPS.
 * The caller MUST first verify that its source wheel-speed units are km/h and
 * supply trustworthy, independent GNSS speed/accuracy measurements.
 *
 * This state machine has no user-facing calibration flow or settings.
 */
internal class WheelSpeedAutoCalibration(
    private val minimumSamples: Int = 60,
    private val maxSamples: Int = 240
) {
    data class Observation(
        val monotonicTimeMs: Long,
        val vehicleSpeedKmh: Double,
        val independentGnssSpeedKmh: Double,
        val gnssSpeedAccuracyMps: Double,
        val yawRateDegreesPerSecond: Double,
        val accelerationMps2: Double,
        val wheelUnitsConfirmedKmh: Boolean
    )

    data class Estimate(
        val multiplier: Double,
        val acceptedSamples: Int,
        val confident: Boolean
    )

    private val ratios = ArrayDeque<Double>()
    private var lastAcceptedAtMs: Long? = null
    private var appliedMultiplier = 1.0

    /** Returns the previous estimate when this update is not reliable. */
    fun observe(sample: Observation): Estimate {
        if (!sample.wheelUnitsConfirmedKmh ||
            !sample.vehicleSpeedKmh.isFinite() ||
            !sample.independentGnssSpeedKmh.isFinite() ||
            !sample.gnssSpeedAccuracyMps.isFinite() ||
            !sample.yawRateDegreesPerSecond.isFinite() ||
            !sample.accelerationMps2.isFinite() ||
            sample.vehicleSpeedKmh !in 35.0..140.0 ||
            sample.independentGnssSpeedKmh !in 35.0..140.0 ||
            sample.gnssSpeedAccuracyMps !in 0.0..0.6 ||
            abs(sample.yawRateDegreesPerSecond) > 2.0 ||
            abs(sample.accelerationMps2) > 0.2
        ) return estimate()

        val last = lastAcceptedAtMs
        if (last != null && sample.monotonicTimeMs < last) reset()
        else if (last != null && sample.monotonicTimeMs - last < 900L)
            return estimate()
        val ratio = sample.independentGnssSpeedKmh / sample.vehicleSpeedKmh
        if (!ratio.isFinite() || ratio !in 0.90..1.10)
            return estimate()

        ratios.addLast(ratio)
        lastAcceptedAtMs = sample.monotonicTimeMs
        if (ratios.size > maxSamples) ratios.removeFirst()
        if (ratios.size >= minimumSamples) {
            val sorted = ratios.sorted()
            val count = sorted.size
            val median = if (count % 2 == 1) sorted[count / 2]
                else (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0
            // Robust gradual convergence, never jump on individual observations.
            appliedMultiplier = (if (ratios.size == minimumSamples) median
                else (0.98 * appliedMultiplier + 0.02 * median))
                .coerceIn(0.90, 1.10)
        }
        return estimate()
    }

    fun estimate(): Estimate = Estimate(
        multiplier = appliedMultiplier,
        acceptedSamples = ratios.size,
        confident = ratios.size >= minimumSamples
    )

    /** Call on explicit vehicle identity change, NOT every reconnect. */
    fun reset() {
        ratios.clear()
        lastAcceptedAtMs = null
        appliedMultiplier = 1.0
    }

    /**
     * For DR only. The caller must require confident=true and valid wheel units.
     * This never alters displayed or recorded factory vehicle speed.
     */
    fun correctedMps(vehicleSpeedKmh: Double): Double? =
        vehicleSpeedKmh.takeIf {
            estimate().confident && it.isFinite() && it in 0.0..200.0
        }?.let { max(0.0, it * appliedMultiplier / 3.6) }
}
