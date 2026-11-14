package app.ft.iccoa

import app.ft.core.GpsSourceMode
import app.ft.dr.DrPathEvidence
import app.ft.dr.FtDrFusionEngine
import app.ft.dr.FtDrRuntime
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ICCOA -> existing FT EKF admission boundary. Raw Carlink values are ALWAYS
 * unqualified. In this exact XA60 Carlink.apk the GyroScope XYZ values have
 * already been Math.floor()'ed at source and cannot recover a real yaw rate.
 * The GPS packet has no horizontal accuracy nor GNSS fix acquisition clock.
 *
 * This bridge observes live raw streams and can accept a second independently
 * PROVEN sensor sample in the future. It never invents accuracy, yaw, clock
 * mapping or PHONE GPS fallback. No wire/protocol change.
 */
internal object IccoaDrInputBridge {
    internal data class Evidence(
        val session: Long,
        val pairedSpeedSamples: Long,
        val speedSlopeRawPerGpsMps: Double?,
        val speedCorrelation: Double?,
        val speedKmhHypothesisSupported: Boolean,
        val quantizedGyroFrames: Long,
        val nonMonotonicGyroFrames: Long,
        val activeVerifiedCar: Boolean
    ) {
        fun reportLine(): String = buildString {
            append("verifiedHU=").append(activeVerifiedCar).append('\n')
            append("speedPairs=").append(pairedSpeedSamples).append('\n')
            append("gearRawPerGpsMpsSlope=")
                .append(speedSlopeRawPerGpsMps ?: "UNKNOWN").append('\n')
            append("gearVsOemGpsSpeedCorrelation=")
                .append(speedCorrelation ?: "UNKNOWN").append('\n')
            append("gearSpeedKmhStatisticalCandidate=")
                .append(speedKmhHypothesisSupported)
                .append(" (NOT_WHEEL_UNITS_VERIFIED)").append('\n')
            append("oemFlooredGyroFrames=").append(quantizedGyroFrames).append('\n')
            append("nonMonotonicGyroFrames=").append(nonMonotonicGyroFrames).append('\n')
            append("gyroYawSource=OEM_MATH_FLOOR_LOSSY_NOT_VERIFIED").append('\n')
            append("gpsFixAccuracyAndAcquisitionClock=NOT_IN_ICCOA_GPS").append('\n')
        }
    }

    /** Only a separate P0 validated source may construct/supply these values. */
    internal data class QualifiedGnss(
        val session: Long,
        val receiverId: String,
        val latitude: Double,
        val longitude: Double,
        val horizontalAccuracyMetres: Double,
        val courseDegrees: Double,
        val fixMonotonicMs: Long,
        val emittedAtMs: Long,
        val genuineCarFixVerified: Boolean,
        val accuracyIndependentlyVerified: Boolean,
        val acquisitionEpochIndependentlyVerified: Boolean,
        val courseVerified: Boolean
    )

    /** Values must come from non-lossy verified vehicle wheel/yaw/clock. */
    internal data class QualifiedMotion(
        val session: Long,
        val receiverId: String,
        val gearRaw: Long,
        val wheelSpeedKmh: Double,
        val vehicleYawRadPerSecond: Double,
        val motionMonotonicMs: Long,
        val emittedAtMs: Long,
        val wheelUnitIndependentlyVerified: Boolean,
        val yawAxisUnitsAndDirectionIndependentlyVerified: Boolean,
        val synchronizedClockIndependentlyVerified: Boolean,
        val gearDirectionIndependentlyVerified: Boolean
    )

    private var session = 0L
    private var carId: String? = null
    private var lastGpsSpeed: Long? = null
    private var lastGpsAtMs = 0L
    private var count = 0L
    private var sx = 0.0
    private var sy = 0.0
    private var sxx = 0.0
    private var syy = 0.0
    private var sxy = 0.0
    private var minSpeed = Double.POSITIVE_INFINITY
    private var maxSpeed = Double.NEGATIVE_INFINITY
    private var gyroQuantized = 0L
    private var gyroClockBackwards = 0L
    private var lastGyroTimestamp: Long? = null

    @Synchronized fun beginSession(epoch: Long) {
        session = epoch
        carId = null
        lastGpsSpeed = null
        lastGpsAtMs = 0L
        count = 0L
        sx = 0.0; sy = 0.0; sxx = 0.0; syy = 0.0; sxy = 0.0
        minSpeed = Double.POSITIVE_INFINITY; maxSpeed = Double.NEGATIVE_INFINITY
        gyroQuantized = 0L; gyroClockBackwards = 0L
        lastGyroTimestamp = null
    }

    /** Cryptographic ICCOA CONTROL identity, NOT a BLE MAC or model label. */
    @Synchronized fun bindAuthenticatedReceiver(id: String) {
        carId = id.takeIf { it.isNotBlank() && it.length <= 256 }
    }

    /** End connection; preserve the last diagnostic counters until next epoch. */
    @Synchronized fun endSession() { carId = null }

    @Synchronized fun rawGps(speedFlooredMps: Long?, arrivalMonoMs: Long) {
        if (speedFlooredMps != null && speedFlooredMps in 3L..40L &&
            arrivalMonoMs > 0L) {
            lastGpsSpeed = speedFlooredMps
            lastGpsAtMs = arrivalMonoMs
        } else lastGpsSpeed = null
    }

    /** Statistical cross-check only. Correlation does NOT qualify wheel units. */
    @Synchronized fun rawGear(speedRaw: Long?, gearRaw: Long?, arrivalMonoMs: Long) {
        val gps = lastGpsSpeed ?: return
        if (gearRaw != 2L || speedRaw == null || speedRaw !in 1L..200L ||
            arrivalMonoMs <= 0L || lastGpsAtMs <= 0L ||
            arrivalMonoMs < lastGpsAtMs || arrivalMonoMs - lastGpsAtMs > 1_200L) return
        val x = gps.toDouble()
        val y = speedRaw.toDouble()
        count++
        sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
        minSpeed = minOf(minSpeed, x); maxSpeed = maxOf(maxSpeed, x)
    }

    /** Android TYPE_GYROSCOPE is rad/s and timestamp ns, but OEM floors XYZ. */
    @Synchronized fun rawGyro(x: Double, y: Double, z: Double, timestampNs: Long?) {
        if (x.isFinite() && y.isFinite() && z.isFinite() &&
            x == kotlin.math.floor(x) && y == kotlin.math.floor(y) &&
            z == kotlin.math.floor(z)) gyroQuantized++
        if (timestampNs != null && timestampNs > 0L) {
            val previous = lastGyroTimestamp
            if (previous != null && timestampNs <= previous) gyroClockBackwards++
            lastGyroTimestamp = timestampNs
        }
    }

    @Synchronized fun evidence(): Evidence {
        val n = count.toDouble()
        val den = n * sxx - sx * sx
        val denY = n * syy - sy * sy
        val slope = if (count >= 3 && den > 0.0) (n * sxy - sx * sy) / den else null
        val corr = if (count >= 3 && den > 0.0 && denY > 0.0)
            (n * sxy - sx * sy) / sqrt(den * denY) else null
        val intercept = if (slope != null) (sy - slope * sx) / n else null
        val plausible = count >= 64 && maxSpeed - minSpeed >= 6.0 &&
            slope != null && slope in 3.4..3.8 &&
            corr != null && corr >= 0.98 &&
            intercept != null && intercept in -1.0..5.0
        return Evidence(session, count, slope, corr, plausible,
            gyroQuantized, gyroClockBackwards, carId != null)
    }

    /**
     * Only a future independently verified direct HU GNSS source can call
     * this. Raw ICCOA Gps does NOT supply the required accuracy or fix epoch.
     */
    @Synchronized fun submitQualifiedGnss(input: QualifiedGnss): Boolean {
        if (carId == null || input.receiverId != carId || input.session != session ||
            !input.genuineCarFixVerified || !input.accuracyIndependentlyVerified ||
            !input.acquisitionEpochIndependentlyVerified ||
            input.horizontalAccuracyMetres !in 0.5..30.0 ||
            !input.latitude.isFinite() || !input.longitude.isFinite() ||
            input.fixMonotonicMs <= 0L || input.emittedAtMs <= 0L) return false
        return FtDrRuntime.acceptVerifiedGnss(FtDrFusionEngine.Gnss(
            latitude = input.latitude,
            longitude = input.longitude,
            horizontalAccuracyMetres = input.horizontalAccuracyMetres,
            courseDegrees = input.courseDegrees,
            fixMonotonicMs = input.fixMonotonicMs,
            emittedAtMs = input.emittedAtMs,
            source = GpsSourceMode.CAR,
            genuineCarFix = true,
            accuracyVerified = true,
            courseVerified = input.courseVerified,
            fixTimeVerified = true
        ))
    }

    /**
     * No call originates from the currently installed floor-quantized OEM
     * gyroscope. A statistically plausible GearInfo scale alone is NOT proof.
     */
    @Synchronized fun submitQualifiedMotion(input: QualifiedMotion): Boolean {
        if (carId == null || input.receiverId != carId || input.session != session ||
            !input.wheelUnitIndependentlyVerified ||
            !input.yawAxisUnitsAndDirectionIndependentlyVerified ||
            !input.synchronizedClockIndependentlyVerified ||
            !input.gearDirectionIndependentlyVerified ||
            input.gearRaw !in 2L..3L ||
            !input.wheelSpeedKmh.isFinite() || input.wheelSpeedKmh !in 0.0..234.0 ||
            !input.vehicleYawRadPerSecond.isFinite() ||
            abs(input.vehicleYawRadPerSecond) > 3.0 ||
            input.motionMonotonicMs <= 0L || input.emittedAtMs <= 0L) return false
        val reverse = input.gearRaw == 3L
        val speed = input.wheelSpeedKmh / 3.6 * (if (reverse) -1.0 else 1.0)
        return FtDrRuntime.acceptVerifiedMotion(FtDrFusionEngine.Motion(
            monotonicMs = input.motionMonotonicMs,
            signedWheelSpeedMps = speed,
            yawRateRadiansPerSecond = input.vehicleYawRadPerSecond,
            wheelUnitsVerified = true,
            yawAxisAndUnitsVerified = true,
            gearDirectionVerified = true,
            reverse = reverse
        ), input.emittedAtMs)
    }
}
