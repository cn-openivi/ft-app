package app.ft.dr

import app.ft.core.GpsSourceMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exactly one per-car EKF state owner. Input validity is a caller contract:
 * ICCOA GearInfo.speed, raw GyroScope XYZ and the serialized GPS timestamp
 * are NOT verified speed, mapped yaw or GNSS fix-time.
 *
 * This adapter is deliberately fail-closed. A live OEM listener may submit
 * observations here only once their independent provenance is established.
 * Unverified GPS still flows to the RAW_UNFILTERED FT baseline, never to EKF.
 */
internal class FtDrFusionEngine(
    private val publish: (Double, Double, DeadReckoningEkf.Estimate, Long) -> Boolean
) {
    data class Gnss(
        val latitude: Double,
        val longitude: Double,
        val horizontalAccuracyMetres: Double,
        val courseDegrees: Double,
        val fixMonotonicMs: Long,
        val emittedAtMs: Long,
        val source: GpsSourceMode,
        val genuineCarFix: Boolean,
        val accuracyVerified: Boolean,
        val courseVerified: Boolean,
        val fixTimeVerified: Boolean
    )

    data class Motion(
        val monotonicMs: Long,
        val signedWheelSpeedMps: Double,
        val yawRateRadiansPerSecond: Double,
        val wheelUnitsVerified: Boolean,
        val yawAxisAndUnitsVerified: Boolean,
        val gearDirectionVerified: Boolean,
        val reverse: Boolean = false
    )

    private val ekf = DeadReckoningEkf()
    private var originLat: Double? = null
    private var originLon: Double? = null
    private var lastMotionMs: Long? = null
    private var lastGnssFixMs: Long? = null
    private var trustedMotionSeen = false
    private var lastPublishAtMs = Long.MIN_VALUE

    fun reset() {
        ekf.reset()
        originLat = null
        originLon = null
        lastMotionMs = null
        lastGnssFixMs = null
        lastPublishAtMs = Long.MIN_VALUE
        trustedMotionSeen = false
    }

    /** No synthetic movement or guessed speed if any trust field is absent. */
    fun onMotion(input: Motion, emittedAtMs: Long): Boolean {
        if (!input.valid() || emittedAtMs <= 0L) {
            val prev = lastMotionMs
            // A malformed frame cannot advance a trusted motion clock.
            // On an actual measured elapsed interval, hold instead of dead reckoning.
            if (prev != null && input.monotonicMs > prev &&
                input.monotonicMs - prev <= 1000L) {
                ekf.holdUnverifiedMotion((input.monotonicMs - prev) / 1000.0)
                lastMotionMs = input.monotonicMs
            }
            return false
        }
        val previous = lastMotionMs
        lastMotionMs = input.monotonicMs
        trustedMotionSeen = true
        if (previous == null || input.monotonicMs <= previous) return false
        val elapsedMs = input.monotonicMs - previous
        if (elapsedMs > 1000L) {
            // Dropout is not a fabricated 1-second displacement.
            ekf.holdUnverifiedMotion(1.0)
            return false
        }
        val estimate = ekf.predict(
            dtSeconds = elapsedMs / 1000.0,
            calibratedSpeedMetresPerSecond = input.signedWheelSpeedMps,
            vehicleYawRateRadiansPerSecond = input.yawRateRadiansPerSecond,
            wheelAndGyroVerified = true
        ) ?: return false
        return publishEstimate(estimate, emittedAtMs)
    }

    /** Correct every independently verified *fix*, regardless of whether DR is active. */
    fun onGnss(input: Gnss): Boolean {
        if (input.source != GpsSourceMode.CAR ||
            !input.genuineCarFix || !input.accuracyVerified ||
            !input.courseVerified || !input.fixTimeVerified ||
            !input.latitude.isFinite() || input.latitude !in -85.0..85.0 ||
            !input.longitude.isFinite() || input.longitude !in -180.0..180.0 ||
            !input.horizontalAccuracyMetres.isFinite() ||
            input.horizontalAccuracyMetres !in 0.5..30.0 ||
            !input.courseDegrees.isFinite() ||
            input.fixMonotonicMs <= 0L || input.emittedAtMs <= 0L ||
            !trustedMotionSeen ||
            (lastGnssFixMs != null && input.fixMonotonicMs <= lastGnssFixMs!!)) return false

        val initialLatitude = originLat
        val initialLongitude = originLon
        if (initialLatitude == null || initialLongitude == null) {
            if (!ekf.anchor(
                eastMetres = 0.0,
                northMetres = 0.0,
                headingDegrees = input.courseDegrees,
                horizontalAccuracyMetres = input.horizontalAccuracyMetres,
                measurementTimestampMs = input.fixMonotonicMs
            )) return false
            originLat = input.latitude
            originLon = input.longitude
        } else {
            val eastNorth = toLocalMetres(
                initialLatitude, initialLongitude, input.latitude, input.longitude)
            if (!ekf.correctSelectedGnss(
                selectedOrigin = GpsSourceMode.CAR,
                receivedOrigin = GpsSourceMode.CAR,
                eastMetres = eastNorth.first,
                northMetres = eastNorth.second,
                horizontalAccuracyMetres = input.horizontalAccuracyMetres,
                sourceValidated = true,
                measurementTimestampMs = input.fixMonotonicMs
            )) return false
        }
        lastGnssFixMs = input.fixMonotonicMs
        return ekf.estimate()?.let { publishEstimate(it, input.emittedAtMs) } == true
    }

    private fun Motion.valid(): Boolean =
        wheelUnitsVerified && yawAxisAndUnitsVerified && gearDirectionVerified &&
            monotonicMs > 0L && signedWheelSpeedMps.isFinite() &&
            kotlin.math.abs(signedWheelSpeedMps) <= 65.0 &&
            yawRateRadiansPerSecond.isFinite() &&
            kotlin.math.abs(yawRateRadiansPerSecond) <= 3.0 &&
            (reverse || signedWheelSpeedMps >= 0.0) &&
            (!reverse || signedWheelSpeedMps <= 0.0)

    private fun publishEstimate(
        estimate: DeadReckoningEkf.Estimate, emittedAtMs: Long
    ): Boolean {
        val lat = originLat ?: return false
        val lon = originLon ?: return false
        if (emittedAtMs <= lastPublishAtMs) return false
        val accepted = publish(lat, lon, estimate, emittedAtMs)
        if (accepted) lastPublishAtMs = emittedAtMs
        return accepted
    }

    private fun toLocalMetres(
        referenceLatitude: Double,
        referenceLongitude: Double,
        latitude: Double,
        longitude: Double
    ): Pair<Double, Double> {
        val latRad = referenceLatitude * PI / 180.0
        val eccentricitySquared = 6.6943799901413165e-3
        val denominator = sqrt(1.0 - eccentricitySquared * sin(latRad) * sin(latRad))
        val eastRadius = 6378137.0 / denominator
        val northRadius = 6378137.0 * (1.0 - eccentricitySquared) /
            (denominator * denominator * denominator)
        return Pair(
            (longitude - referenceLongitude) * PI / 180.0 * eastRadius * cos(latRad),
            (latitude - referenceLatitude) * PI / 180.0 * northRadius
        )
    }
}
