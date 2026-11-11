package app.ft.dr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import app.ft.core.GpsSourceMode

/**
 * A protocol-neutral 2D prediction/filter using the embedded MIT TinyEKF port.
 *
 * Input units are already normalised by the caller:
 *   east/north [metres], speed [m/s], gyro yaw [rad/s], dt [seconds].
 *
 * ICCOA/Baidu raw IMU values CANNOT be fed here until their axis, sign,
 * timestamp and units have been independently established. Car GNSS and
 * Android Auto sensor channels are intentionally NOT connected to this model.
 */
internal class DeadReckoningEkf {
    private val filter = TinyEkf(STATE_SIZE, GPS_MEASUREMENTS)
    // One EKF: no rolling five-fix GNSS history or separate odometry veto.
    // Track only whether actual wheel/yaw evidence was interrupted.
    private var unobservedMotion = false
    private var initialized = false
    private var secondsWithoutGnss = 0.0
    private var previousWheelSpeedMetresPerSecond: Double? = null

    data class Estimate(
        val eastMetres: Double,
        val northMetres: Double,
        val headingDegrees: Double,
        val horizontalUncertaintyMetres: Double,
        val secondsSinceGnss: Double,
        val estimated: Boolean
    )

    fun reset() {
        initialized = false
        secondsWithoutGnss = 0.0
        previousWheelSpeedMetresPerSecond = null
        unobservedMotion = false
        // Resetting the model invalidates the preceding trust anchor.
    }

    /** A genuine, reliable GNSS fix, with verified true heading, starts DR. */
    fun anchor(
        eastMetres: Double,
        northMetres: Double,
        headingDegrees: Double,
        horizontalAccuracyMetres: Double,
        measurementTimestampMs: Long? = null
    ): Boolean {
        if (!eastMetres.isFinite() || !northMetres.isFinite() ||
            !headingDegrees.isFinite() || !horizontalAccuracyMetres.isFinite() ||
            horizontalAccuracyMetres !in 0.5..30.0 || initialized) return false
        // Anchoring is a one-time operation. All later GNSS measurements
        // correct this same filter, never reset it based on packet order.
        val heading = headingDegrees * PI / 180.0
        filter.reset(
            doubleArrayOf(eastMetres, northMetres, normalize(heading), 0.0),
            doubleArrayOf(
                horizontalAccuracyMetres * horizontalAccuracyMetres,
                horizontalAccuracyMetres * horizontalAccuracyMetres,
                sq(8.0 * PI / 180.0), sq(0.02)
            )
        )
        initialized = true
        secondsWithoutGnss = 0.0
        previousWheelSpeedMetresPerSecond = null
        unobservedMotion = false
        return true
    }

    /**
     * Prediction must be explicitly authorised by verified/calibrated wheel
     * SIGNED speed and verified mapped OEM yaw. Negative speed is permitted
     * only when the upstream adapter has independently verified a fresh
     * REVERSE gear + calibrated wheel velocity; callers must NEVER infer
     * reverse from a missing/old gear field or raw ICCOA speed alone.
     * Never infer verification from a present-but-undecoded sensor packet.
     */
    fun predict(
        dtSeconds: Double,
        calibratedSpeedMetresPerSecond: Double,
        vehicleYawRateRadiansPerSecond: Double,
        wheelAndGyroVerified: Boolean,
        longitudinalAccelerationMetresPerSecond2: Double? = null,
        accelerationVerified: Boolean = false
    ): Estimate? {
        if (!initialized || !wheelAndGyroVerified ||
            !dtSeconds.isFinite() || dtSeconds !in 0.001..1.0 ||
            !calibratedSpeedMetresPerSecond.isFinite() ||
            abs(calibratedSpeedMetresPerSecond) > 65.0 ||
            !vehicleYawRateRadiansPerSecond.isFinite() ||
            abs(vehicleYawRateRadiansPerSecond) > 3.0 ||
            (accelerationVerified && (longitudinalAccelerationMetresPerSecond2 == null ||
                !longitudinalAccelerationMetresPerSecond2.isFinite() ||
                abs(longitudinalAccelerationMetresPerSecond2) > 20.0))) return null

        val x = filter.state()
        val distance = calibratedSpeedMetresPerSecond * dtSeconds
        val mid = x[HEADING] +
            (vehicleYawRateRadiansPerSecond - x[GYRO_BIAS]) * dtSeconds / 2.0
        val next = x.copyOf()
        next[EAST] += distance * sin(mid)
        next[NORTH] += distance * cos(mid)
        next[HEADING] = normalize(
            x[HEADING] + (vehicleYawRateRadiansPerSecond - x[GYRO_BIAS]) * dtSeconds
        )
        val f = DoubleArray(STATE_SIZE * STATE_SIZE) { i ->
            if (i / STATE_SIZE == i % STATE_SIZE) 1.0 else 0.0
        }
        f[EAST * STATE_SIZE + HEADING] = distance * cos(mid)
        f[NORTH * STATE_SIZE + HEADING] = -distance * sin(mid)
        f[EAST * STATE_SIZE + GYRO_BIAS] = -0.5 * distance * dtSeconds * cos(mid)
        f[NORTH * STATE_SIZE + GYRO_BIAS] = 0.5 * distance * dtSeconds * sin(mid)
        f[HEADING * STATE_SIZE + GYRO_BIAS] = -dtSeconds

        val q = DoubleArray(STATE_SIZE * STATE_SIZE)
        // Reverse is signed displacement, but uncertainty scales with the
        // absolute path length, never with signed/net travel.
        val travelledStdMetres = 0.15 + 0.03 * abs(distance)
        // An independently verified longitudinal accelerometer can flag
        // disagreement with wheel-speed derivatives (slip or transient lag).
        // Increase EKF motion uncertainty; do not create a second filter,
        // replace wheel velocity or double-integrate acceleration.
        val previous = previousWheelSpeedMetresPerSecond
        val accelMismatch = if (accelerationVerified && previous != null)
            abs((calibratedSpeedMetresPerSecond - previous) / dtSeconds -
                requireNotNull(longitudinalAccelerationMetresPerSecond2)) else 0.0
        val motionUncertaintyFactor = 1.0 +
            (accelMismatch / 2.0).coerceIn(0.0, 8.0)
        q[EAST * STATE_SIZE + EAST] =
            sq(travelledStdMetres) * dtSeconds * motionUncertaintyFactor
        q[NORTH * STATE_SIZE + NORTH] =
            sq(travelledStdMetres) * dtSeconds * motionUncertaintyFactor
        q[HEADING * STATE_SIZE + HEADING] = sq(0.01) * dtSeconds
        q[GYRO_BIAS * STATE_SIZE + GYRO_BIAS] = sq(0.0002) * dtSeconds
        filter.predict(next, f, q)
        previousWheelSpeedMetresPerSecond = calibratedSpeedMetresPerSecond
        secondsWithoutGnss += dtSeconds
        return estimate()
    }

    /**
     * Explicit coasting step when either wheel speed or mapped yaw is NOT
     * independently verified. Freeze position and heading, never integrate
     * guessed motion, but increase covariance and GNSS-outage age.
     *
     * This is deliberately not called implicitly by predict(): a rejected
     * malformed sample must not invent elapsed time. The future validated
     * sensor adapter must supply measured monotonic elapsed seconds.
     */
    fun holdUnverifiedMotion(dtSeconds: Double): Estimate? {
        if (!initialized || !dtSeconds.isFinite() ||
            dtSeconds !in 0.001..1.0) return null
        val frozen = filter.state()
        val identity = DoubleArray(STATE_SIZE * STATE_SIZE) { i ->
            if (i / STATE_SIZE == i % STATE_SIZE) 1.0 else 0.0
        }
        val q = DoubleArray(STATE_SIZE * STATE_SIZE)
        // Conservative uncertainty growth with no assumed displacement.
        val positionVariancePerSecond = sq(3.0)
        q[EAST * STATE_SIZE + EAST] = positionVariancePerSecond * dtSeconds
        q[NORTH * STATE_SIZE + NORTH] = positionVariancePerSecond * dtSeconds
        q[HEADING * STATE_SIZE + HEADING] = sq(0.03) * dtSeconds
        q[GYRO_BIAS * STATE_SIZE + GYRO_BIAS] = sq(0.0002) * dtSeconds
        filter.predict(frozen, identity, q)
        previousWheelSpeedMetresPerSecond = null
        unobservedMotion = true
        secondsWithoutGnss += dtSeconds
        return estimate()
    }

    /**
     * A verified independent GNSS position corrects the state through EKF
     * innovation gating. Bogus fixes cannot instantly teleport the estimate.
     */
    fun correctGnss(
        eastMetres: Double,
        northMetres: Double,
        horizontalAccuracyMetres: Double,
        genuineGnss: Boolean,
        measurementTimestampMs: Long? = null
    ): Boolean {
        if (!initialized || !genuineGnss ||
            !eastMetres.isFinite() || !northMetres.isFinite() ||
            !horizontalAccuracyMetres.isFinite() ||
            horizontalAccuracyMetres !in 0.5..30.0) return false

        val x = filter.state()
        val p = filter.covariance()
        val uncertainty = sq(max(0.5, horizontalAccuracyMetres))
        val de = eastMetres - x[EAST]
        val dn = northMetres - x[NORTH]
        // Full 2D normalized innovation squared (NIS), including the
        // east/north cross covariance predicted from IMU yaw and wheel speed.
        // A single GPS multipath excursion should not teleport the trajectory.
        val s00 = p[EAST * STATE_SIZE + EAST] + uncertainty
        val s11 = p[NORTH * STATE_SIZE + NORTH] + uncertainty
        val s01 = (p[EAST * STATE_SIZE + NORTH] +
            p[NORTH * STATE_SIZE + EAST]) / 2.0
        val determinant = s00 * s11 - s01 * s01
        if (!determinant.isFinite() || determinant <= 0.0) return false
        val innovationEnergy =
            (s11 * sq(de) - 2.0 * s01 * de * dn + s00 * sq(dn)) / determinant
        if (!innovationEnergy.isFinite()) return false
        if (innovationEnergy > 25.0 && unobservedMotion) {
            // Wheel/gyro stopped proving displacement. A real, independently
            // validated GNSS observation may re-anchor position immediately,
            // without arbitrary sample counts, cooldowns or old-fix timers.
            // Keep the existing heading and gyro bias; do not invent a new yaw.
            filter.reset(
                doubleArrayOf(eastMetres, northMetres, x[HEADING], x[GYRO_BIAS]),
                doubleArrayOf(uncertainty, uncertainty,
                    max(p[HEADING * STATE_SIZE + HEADING], sq(8.0 * PI / 180.0)),
                    max(p[GYRO_BIAS * STATE_SIZE + GYRO_BIAS], sq(0.02)))
            )
            unobservedMotion = false
            previousWheelSpeedMetresPerSecond = null
            secondsWithoutGnss = 0.0
            return true
        }
        // With continuous verified wheel/yaw evidence, reject a GNSS jump
        // outside the same EKF innovation ellipse; no extra historical veto.
        if (innovationEnergy > 25.0) return false

        // Mild multipath/urban-canyon disagreement is fed into the SAME
        // TinyEKF update with less measurement weight, not a second GPS
        // smoothing filter or artificial DR timeout.
        val weightInflation = (innovationEnergy / 9.0).coerceIn(1.0, 4.0)
        val observationVariance = uncertainty * weightInflation
        val h = DoubleArray(GPS_MEASUREMENTS * STATE_SIZE)
        h[0] = 1.0
        h[STATE_SIZE + 1] = 1.0
        val r = doubleArrayOf(observationVariance, 0.0,
            0.0, observationVariance)
        if (!filter.update(
            doubleArrayOf(eastMetres, northMetres),
            doubleArrayOf(x[EAST], x[NORTH]), h, r
        )) return false
        secondsWithoutGnss = 0.0
        unobservedMotion = false
        return true
    }

    /**
     * No artificial timeout or uncertainty cutoff: keep estimating as long as
     * verified wheel/yaw measurements continue. The uncertainty grows with
     * covariance and callers must label it as estimated, never actual GNSS.
     * Non-finite state/covariance is still rejected as numerically invalid.
     */
    /**
     * The chosen origin is a hard source gate, independent of EKF quality.
     * Neither selected GPS mode may silently use the other as a backup.
     * Both can use the SAME verified vehicle wheel/IMU prediction above.
     */
    fun correctSelectedGnss(
        selectedOrigin: GpsSourceMode,
        receivedOrigin: GpsSourceMode,
        eastMetres: Double,
        northMetres: Double,
        horizontalAccuracyMetres: Double,
        sourceValidated: Boolean,
        measurementTimestampMs: Long? = null
    ): Boolean {
        if (!selectedOrigin.acceptsGnssFrom(receivedOrigin)) return false
        return correctGnss(eastMetres, northMetres,
            horizontalAccuracyMetres, sourceValidated, measurementTimestampMs)
    }

    fun estimate(): Estimate? {
        if (!initialized) return null
        val x = filter.state()
        val p = filter.covariance()
        val uncertainty = sqrt(max(0.0, max(p[0], p[STATE_SIZE + 1])))
        if (!uncertainty.isFinite() || !x.all(Double::isFinite)) return null
        val degrees = (normalize(x[HEADING]) * 180.0 / PI + 360.0) % 360.0
        return Estimate(
            eastMetres = x[EAST],
            northMetres = x[NORTH],
            headingDegrees = degrees,
            horizontalUncertaintyMetres = uncertainty,
            secondsSinceGnss = secondsWithoutGnss,
            estimated = secondsWithoutGnss > 0.0
        )
    }

    private fun sq(v: Double) = v * v

    private fun normalize(v: Double): Double {
        var a = v % (2.0 * PI)
        if (a >= PI) a -= 2.0 * PI
        if (a < -PI) a += 2.0 * PI
        return a
    }

    private companion object {
        const val STATE_SIZE = 4
        const val GPS_MEASUREMENTS = 2
        const val EAST = 0
        const val NORTH = 1
        const val HEADING = 2
        const val GYRO_BIAS = 3
    }
}
