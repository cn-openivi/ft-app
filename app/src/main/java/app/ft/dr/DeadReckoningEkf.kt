package app.ft.dr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

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
    private var initialized = false
    private var secondsWithoutGnss = 0.0

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
    }

    /** A genuine, reliable GNSS fix, with verified true heading, starts DR. */
    fun anchor(
        eastMetres: Double,
        northMetres: Double,
        headingDegrees: Double,
        horizontalAccuracyMetres: Double
    ): Boolean {
        if (!eastMetres.isFinite() || !northMetres.isFinite() ||
            !headingDegrees.isFinite() || !horizontalAccuracyMetres.isFinite() ||
            horizontalAccuracyMetres !in 0.5..30.0) return false
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
        return true
    }

    /**
     * Prediction must be explicitly authorised by verified/calibrated wheel
     * speed and verified mapped OEM yaw. Never infer verification from a
     * present-but-undecoded sensor packet.
     */
    fun predict(
        dtSeconds: Double,
        calibratedSpeedMetresPerSecond: Double,
        vehicleYawRateRadiansPerSecond: Double,
        wheelAndGyroVerified: Boolean
    ): Estimate? {
        if (!initialized || !wheelAndGyroVerified ||
            !dtSeconds.isFinite() || dtSeconds !in 0.001..1.0 ||
            !calibratedSpeedMetresPerSecond.isFinite() ||
            calibratedSpeedMetresPerSecond !in 0.0..65.0 ||
            !vehicleYawRateRadiansPerSecond.isFinite() ||
            abs(vehicleYawRateRadiansPerSecond) > 3.0) return null

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
        val travelledStdMetres = 0.15 + 0.03 * distance
        q[EAST * STATE_SIZE + EAST] = sq(travelledStdMetres) * dtSeconds
        q[NORTH * STATE_SIZE + NORTH] = sq(travelledStdMetres) * dtSeconds
        q[HEADING * STATE_SIZE + HEADING] = sq(0.01) * dtSeconds
        q[GYRO_BIAS * STATE_SIZE + GYRO_BIAS] = sq(0.0002) * dtSeconds
        filter.predict(next, f, q)
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
        genuineGnss: Boolean
    ): Boolean {
        if (!initialized || !genuineGnss ||
            !eastMetres.isFinite() || !northMetres.isFinite() ||
            !horizontalAccuracyMetres.isFinite() ||
            horizontalAccuracyMetres !in 0.5..30.0) return false

        val x = filter.state()
        val p = filter.covariance()
        val uncertainty = sq(horizontalAccuracyMetres)
        val de = eastMetres - x[EAST]
        val dn = northMetres - x[NORTH]
        // 5-sigma simple 2D innovation gate, without assuming false precision.
        val innovationEnergy =
            sq(de) / max(1e-6, p[EAST * STATE_SIZE + EAST] + uncertainty) +
            sq(dn) / max(1e-6, p[NORTH * STATE_SIZE + NORTH] + uncertainty)
        if (!innovationEnergy.isFinite() || innovationEnergy > 25.0) return false

        val h = DoubleArray(GPS_MEASUREMENTS * STATE_SIZE)
        h[0] = 1.0
        h[STATE_SIZE + 1] = 1.0
        val r = doubleArrayOf(uncertainty, 0.0, 0.0, uncertainty)
        if (!filter.update(
            doubleArrayOf(eastMetres, northMetres),
            doubleArrayOf(x[EAST], x[NORTH]), h, r
        )) return false
        secondsWithoutGnss = 0.0
        return true
    }

    /**
     * No artificial timeout or uncertainty cutoff: keep estimating as long as
     * verified wheel/yaw measurements continue. The uncertainty grows with
     * covariance and callers must label it as estimated, never actual GNSS.
     * Non-finite state/covariance is still rejected as numerically invalid.
     */
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
