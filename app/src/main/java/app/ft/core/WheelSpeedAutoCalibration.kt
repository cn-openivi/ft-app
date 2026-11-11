package app.ft.core

import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One correction multiplier per independently identified vehicle, learned only
 * from long, high-quality straight GNSS + confirmed wheel odometry segments.
 *
 * This is not a speedometer correction and NEVER turns OEM GearInfo.speed or
 * floored ICCOA GPS speed into verified wheel/independent GNSS measurements.
 * No OEM adapter may set verification flags without live provenance proof.
 * The owner persists Snapshot only after a completed accepted segment, never
 * from a sensor callback. There is no seasonal/tyre profile.
 */
internal class WheelSpeedAutoCalibration(
    private val minimumSegments: Int = 3,
    private val maxSegments: Int = 9
) {
    init { require(minimumSegments in 2..maxSegments && maxSegments <= 32) }

    data class WheelMotion(
        val monotonicMs: Long,
        val forwardSpeedMps: Double,
        val yawRateRadPerSecond: Double,
        val longitudinalAccelerationMps2: Double,
        val verifiedWheelAndYawUnits: Boolean,
        val verifiedForwardGear: Boolean
    )

    data class IndependentGnss(
        val fixMonotonicMs: Long,
        val latitude: Double,
        val longitude: Double,
        val horizontalAccuracyMetres: Double,
        val independentCarGnssVerified: Boolean,
        val monotonicFixEpochVerified: Boolean
    )

    data class Estimate(
        val multiplier: Double,
        val acceptedSegments: Int,
        val confident: Boolean,
        val pendingPersistence: Boolean
    )

    data class Snapshot(val vehicleId: String, val multiplier: Double)

    private var vehicleId: String? = null
    private val completedRatios = ArrayDeque<Double>()
    private var multiplier = 1.0
    private var confident = false
    private var pendingPersistence = false
    private var anchor: IndependentGnss? = null
    private var previousFix: IndependentGnss? = null
    private var previousFixWheelMetres = 0.0
    private var previousWheel: WheelMotion? = null
    private var segmentWheelMetres = 0.0
    private var segmentAbsoluteYaw = 0.0
    private var fixCount = 0

    /** Must be a verified, stable car identifier; never a guessed model name. */
    fun bindVehicle(verifiedVehicleId: String, saved: Snapshot? = null): Boolean {
        if (verifiedVehicleId.isBlank()) return false
        if (vehicleId == verifiedVehicleId) return true
        vehicleId = verifiedVehicleId
        multiplier = 1.0
        confident = false
        pendingPersistence = false
        completedRatios.clear()
        resetSegment()
        if (saved != null && saved.vehicleId == verifiedVehicleId &&
            saved.multiplier.isFinite() && saved.multiplier in 0.90..1.10) {
            multiplier = saved.multiplier
            confident = true
        }
        return true
    }

    fun observeWheel(sample: WheelMotion): Estimate {
        if (vehicleId == null || !sample.verifiedWheelAndYawUnits ||
            !sample.verifiedForwardGear || sample.monotonicMs <= 0L ||
            !sample.forwardSpeedMps.isFinite() ||
            sample.forwardSpeedMps !in 10.0..39.0 ||
            !sample.yawRateRadPerSecond.isFinite() ||
            abs(sample.yawRateRadPerSecond) > 0.015 ||
            !sample.longitudinalAccelerationMps2.isFinite() ||
            abs(sample.longitudinalAccelerationMps2) > 0.5) {
            resetSegment()
            return estimate()
        }
        val previous = previousWheel
        if (previous != null) {
            val dtMs = sample.monotonicMs - previous.monotonicMs
            // Calibration requires actual high-rate motion samples; a missing
            // interval must not be interpreted as zero or made up as distance.
            if (dtMs !in 20L..500L) {
                resetSegment()
            } else if (anchor != null) {
                val dt = dtMs / 1000.0
                segmentWheelMetres +=
                    (previous.forwardSpeedMps + sample.forwardSpeedMps) * 0.5 * dt
                segmentAbsoluteYaw += abs(sample.yawRateRadPerSecond) * dt
                if (segmentAbsoluteYaw > 0.10 || segmentWheelMetres > 2_100.0)
                    resetSegment()
            }
        }
        previousWheel = sample
        return estimate()
    }

    /**
     * Exact GNSS *fix* monotonic epoch must be independently proven.
     * OEM ICCOA packet serialization timestamp is NOT accepted as fix time.
     * Missing GNSS cannot change the multiplier; prolonged gaps simply discard
     * an unfinished calibration segment, unrelated to DR activation.
     */
    fun observeGnss(fix: IndependentGnss): Estimate {
        val motion = previousWheel
        if (vehicleId == null || !fix.independentCarGnssVerified ||
            !fix.monotonicFixEpochVerified || fix.fixMonotonicMs <= 0L ||
            !fix.latitude.isFinite() || fix.latitude !in -85.0..85.0 ||
            !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0 ||
            !fix.horizontalAccuracyMetres.isFinite() ||
            fix.horizontalAccuracyMetres !in 0.5..3.0 ||
            motion == null || abs(motion.monotonicMs - fix.fixMonotonicMs) > 300L) {
            resetSegment()
            return estimate()
        }
        val first = anchor
        val previous = previousFix
        if (first == null || previous == null) {
            anchor = fix
            previousFix = fix
            previousFixWheelMetres = segmentWheelMetres
            fixCount = 1
            return estimate()
        }
        val intervalMs = fix.fixMonotonicMs - previous.fixMonotonicMs
        val wheelDelta = segmentWheelMetres - previousFixWheelMetres
        val gpsDelta = distanceMetres(previous, fix)
        if (intervalMs !in 300L..6_000L || wheelDelta <= 0.0 ||
            gpsDelta > wheelDelta * 1.15 + 10.0 ||
            gpsDelta + 10.0 < wheelDelta * 0.80 ||
            segmentAbsoluteYaw > 0.10) {
            resetSegment()
            return estimate()
        }
        previousFix = fix
        previousFixWheelMetres = segmentWheelMetres
        fixCount++
        if (segmentWheelMetres < 1_000.0) return estimate()
        if (segmentWheelMetres > 2_000.0 || fixCount < 8) {
            resetSegment()
            return estimate()
        }
        val chord = distanceMetres(first, fix)
        val ratio = chord / segmentWheelMetres
        if (ratio.isFinite() && ratio in 0.90..1.10 &&
            chord > segmentWheelMetres * 0.96) {
            val initialMedian = median(completedRatios)
            // Once multiple segments agree, reject isolated route/GNSS bias;
            // never adapt the coefficient to one multipath-contaminated road.
            if (initialMedian == null || completedRatios.size < minimumSegments ||
                abs(ratio - initialMedian) <= 0.02) {
                completedRatios.addLast(ratio)
                if (completedRatios.size > maxSegments) completedRatios.removeFirst()
                if (completedRatios.size >= minimumSegments) {
                    multiplier = (0.90 * multiplier +
                        0.10 * requireNotNull(median(completedRatios)))
                        .coerceIn(0.90, 1.10)
                    confident = true
                    pendingPersistence = true
                }
            }
        }
        resetSegment()
        return estimate()
    }

    /** Does not write to disk. Call once when an accepted segment sets dirty. */
    fun snapshotForPersistence(): Snapshot? = vehicleId?.takeIf {
        confident && pendingPersistence
    }?.let { Snapshot(it, multiplier) }

    fun confirmPersisted(snapshot: Snapshot): Boolean {
        if (snapshot.vehicleId != vehicleId || snapshot.multiplier != multiplier ||
            !pendingPersistence) return false
        pendingPersistence = false
        return true
    }

    fun correctedMps(verifiedForwardWheelSpeedMps: Double): Double? =
        verifiedForwardWheelSpeedMps.takeIf {
            vehicleId != null && confident && it.isFinite() && it in 0.0..65.0
        }?.times(multiplier)

    fun estimate(): Estimate = Estimate(
        multiplier, completedRatios.size, confident, pendingPersistence
    )

    fun resetSegment() {
        anchor = null
        previousFix = null
        previousFixWheelMetres = 0.0
        previousWheel = null
        segmentWheelMetres = 0.0
        segmentAbsoluteYaw = 0.0
        fixCount = 0
    }

    private fun median(values: ArrayDeque<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted() // only on a completed 1–2 km segment
        val centre = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[centre]
        else (sorted[centre - 1] + sorted[centre]) / 2.0
    }

    private fun distanceMetres(a: IndependentGnss, b: IndependentGnss): Double {
        val dLat = (b.latitude - a.latitude) * PI / 180.0
        val dLon = (b.longitude - a.longitude) * PI / 180.0
        val h = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(a.latitude * PI / 180.0) * cos(b.latitude * PI / 180.0) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        return 2.0 * 6_371_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
