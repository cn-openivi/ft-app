package app.ft.dr

import app.ft.core.VehicleLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FT190: continuously running *observational* car-GPS / GearInfo TinyEKF.
 *
 * The existing verified FtDrRuntime requires independently calibrated OEM
 * yaw, fix acquisition clock and horizontal accuracy. The FAW Carlink wire
 * currently supplies NONE of them. This model uses the SAME TinyEKF math but
 * NEVER supplies a false "verified vehicle motion" / AA FUSED location.
 *
 * GPS and forward GearInfo train the filter on every accepted sensor event.
 * Underground/underpass/short dropout is a normal GNSS observation gap, not
 * proof that Carlink failed. With no usable yaw, last measured GPS course
 * is HELD and covariance grows; this model cannot resolve real tunnel turns.
 * Predictions are available to FT native clients as PROVISIONAL, not as
 * an Android Auto GPS fix, and are never silently sent to AA.
 */
internal class CarTunnelKalman {
    companion object {
        private const val MODEL_GPS_STD_METRES = 30.0 // ASSUMED, NOT RECEIVER ACCURACY
        private const val MAX_CONTIGUOUS_GEAR_MS = 2_000L
        private const val GPS_RECEIPT_GAP_MS = 3_000L // Mode label only, NOT a GPS TTL
        private const val MAX_GEAR_KMH = 234L
    }

    enum class Mode {
        NO_GPS, GPS_NO_COURSE, GPS_OBSERVED, TUNNEL_COURSE_HELD, TUNNEL_NO_DIRECTION
    }

    data class Estimate(
        val latitude: Double,
        val longitude: Double,
        val courseDegrees: Double?,
        val modelStdMetres: Double,
        val gpsReceiptGapMs: Long,
        val travelledModelMetres: Double,
        val mode: Mode,
        val lastCarGpsAtMonoMs: Long
    ) {
        val independentlyVerifiedDeadReckoning = false
        val eligibleForAaSensorLocation = false
        val modelAccuracyNotReceiverAccuracy = true
    }

    data class Evidence(
        val mode: Mode,
        val gpsAccepted: Long,
        val gearAccepted: Long,
        val predictedForwardIntervals: Long,
        val noHeadingOrDirectionIntervals: Long,
        val gearTimeGaps: Long,
        val lastGpsReceiptGapMs: Long,
        val lastModelStdMetres: Double?,
        val travelledModelMetres: Double,
        val active: Boolean
    ) {
        fun reportLine(): String = listOf(
            "format=CAR_TUNNEL_KALMAN_V1",
            "status=PROVISIONAL_NOT_VERIFIED_DR",
            "mode=$mode",
            "carGpsPacketsAccepted=$gpsAccepted gearPacketsAccepted=$gearAccepted",
            "predictedForwardIntervals=$predictedForwardIntervals",
            "noHeadingOrDirectionIntervals=$noHeadingOrDirectionIntervals",
            "gearTimeGaps=$gearTimeGaps",
            "lastGpsReceiptGapMs=$lastGpsReceiptGapMs",
            "modelStdMetres=${lastModelStdMetres ?: "NOT_AVAILABLE"}",
            "travelledModelMetres=$travelledModelMetres",
            "active=$active",
            "gnssGapMayBeTunnelOrParking=true",
            "carlinkFaultInferredFromSilence=NO",
            "gyroYawVerified=NO",
            "receiverGnssAccuracyVerified=NO",
            "estimatedFixForwardedToAa=NO",
            "automaticPhoneGpsFallback=NO"
        ).joinToString("\n", postfix = "\n")
    }

    private val ekf = DeadReckoningEkf()
    private val _current = MutableStateFlow<Estimate?>(null)
    val current: StateFlow<Estimate?> = _current
    private var active = false
    private var originLat: Double? = null
    private var originLon: Double? = null
    private var lastGpsMonoMs = 0L
    private var lastGearMonoMs = 0L
    private var courseObserved = false
    private var gpsCount = 0L
    private var gearCount = 0L
    private var predictedCount = 0L
    private var heldCount = 0L
    private var discontinuityCount = 0L
    private var modelDistance = 0.0
    private var lastMode = Mode.NO_GPS
    private var lastGapMs = 0L
    private var lastReportedModelStdMetres: Double? = null

    @Synchronized fun beginSession() {
        ekf.reset()
        active = true
        originLat = null
        originLon = null
        lastGpsMonoMs = 0L
        lastGearMonoMs = 0L
        courseObserved = false
        gpsCount = 0L
        gearCount = 0L
        predictedCount = 0L
        heldCount = 0L
        discontinuityCount = 0L
        modelDistance = 0.0
        lastMode = Mode.NO_GPS
        lastGapMs = 0L
        lastReportedModelStdMetres = null
        _current.value = null
    }

    /** Preserve the last numerical report for ZIP export after disconnect. */
    @Synchronized fun endSession() {
        active = false
        lastGearMonoMs = 0L
        _current.value = null // Never retain a previous car's position in live UI
    }

    private fun movingCourse(sample: VehicleLocation): Double? {
        val speed = sample.speedRaw
        val bearing = sample.headingRaw
        return if (sample.source == "ICCOA" &&
            sample.coordinatesUnit == "ANDROID_LOCATION_WGS84" &&
            sample.speedUnit == "ANDROID_LOCATION_MPS_FLOORED" &&
            sample.headingUnit == "ANDROID_LOCATION_DEGREES_FLOORED" &&
            speed != null && speed >= 2L &&
            bearing != null && bearing in 0L..359L) bearing.toDouble() else null
    }

    /** One actual OEM GPS sensor event; NEVER replay a stored GearInfo snapshot. */
    @Synchronized fun onGps(sample: VehicleLocation, receiptMonoMs: Long): Estimate? {
        if (!active || sample.source != "ICCOA" ||
            sample.coordinatesUnit != "ANDROID_LOCATION_WGS84" ||
            receiptMonoMs <= 0L ||
            (lastGpsMonoMs != 0L && receiptMonoMs <= lastGpsMonoMs)) return null
        val lat = sample.displayLatitude ?: return null
        val lon = sample.displayLongitude ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        if (lat !in -85.0..85.0 || lon !in -180.0..180.0) return null
        val course = movingCourse(sample)
        if (originLat == null) {
            if (!ekf.anchorUnverifiedGps(0.0, 0.0, course, MODEL_GPS_STD_METRES)) return null
            originLat = lat
            originLon = lon
            courseObserved = course != null
        } else {
            val (east, north) = toLocal(requireNotNull(originLat), requireNotNull(originLon),
                lat, lon)
            if (!ekf.correctGnss(
                    east, north, MODEL_GPS_STD_METRES, genuineGnss = true
                )) return null
            if (course != null) {
                if (ekf.correctObservedCourse(course)) courseObserved = true
            }
        }
        lastGpsMonoMs = receiptMonoMs
        gpsCount++
        lastGapMs = 0L
        lastMode = if (courseObserved) Mode.GPS_OBSERVED else Mode.GPS_NO_COURSE
        return publish(receiptMonoMs)
    }

    /**
     * Speed is known in km/h on this exact FAW XA60, not a calibrated wheel
     * odometer. Forward gear 2 is observed in source field logs. No reverse
     * prediction from a missing/unknown gear enum: course-over-ground is NOT
     * car nose heading, and reverse silently flips travel direction.
     */
    @Synchronized fun onGear(
        speedKmh: Long?, gearRaw: Long?, receiptMonoMs: Long
    ): Estimate? {
        if (!active || speedKmh == null || speedKmh !in 0L..MAX_GEAR_KMH ||
            receiptMonoMs <= 0L) return null
        gearCount++
        val previous = lastGearMonoMs
        lastGearMonoMs = receiptMonoMs
        if (previous <= 0L || receiptMonoMs <= previous || originLat == null) return null
        val dtMs = receiptMonoMs - previous
        val dt = dtMs / 1_000.0
        val directionKnown = gearRaw == 2L
        val canPredict = dtMs <= MAX_CONTIGUOUS_GEAR_MS &&
            (speedKmh == 0L || (courseObserved && directionKnown))
        when {
            dtMs > MAX_CONTIGUOUS_GEAR_MS -> {
                ekf.holdUnverifiedMotion(dt)
                discontinuityCount++
                heldCount++
            }
            canPredict -> {
                val estimate = ekf.predictUnverifiedYaw(dt, speedKmh / 3.6) ?: return null
                if (!estimate.horizontalUncertaintyMetres.isFinite()) return null
                if (speedKmh > 0L) {
                    modelDistance += speedKmh / 3.6 * dt
                    predictedCount++
                }
            }
            else -> {
                ekf.holdUnverifiedMotion(dt)
                heldCount++
            }
        }
        val gap = if (lastGpsMonoMs > 0L && receiptMonoMs >= lastGpsMonoMs)
            receiptMonoMs - lastGpsMonoMs else 0L
        lastGapMs = gap
        lastMode = when {
            gap < GPS_RECEIPT_GAP_MS && courseObserved -> Mode.GPS_OBSERVED
            gap < GPS_RECEIPT_GAP_MS -> Mode.GPS_NO_COURSE
            !courseObserved || !directionKnown -> Mode.TUNNEL_NO_DIRECTION
            else -> Mode.TUNNEL_COURSE_HELD
        }
        return publish(receiptMonoMs)
    }

    private fun publish(receiptMonoMs: Long): Estimate? {
        val anchorLat = originLat ?: return null
        val anchorLon = originLon ?: return null
        val estimated = ekf.estimate() ?: return null
        val latRad = Math.toRadians(anchorLat)
        val e2 = 6.6943799901413165e-3
        val sinLat = sin(latRad)
        val denominator = sqrt(1.0 - e2 * sinLat * sinLat)
        val re = 6378137.0 / denominator
        val rn = 6378137.0 * (1.0 - e2) / (denominator * denominator * denominator)
        val lat = anchorLat + estimated.northMetres / rn * 180.0 / PI
        val lon = anchorLon + estimated.eastMetres / (re * cos(latRad)) * 180.0 / PI
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -85.0..85.0 || lon !in -180.0..180.0) return null
        val next = Estimate(
            latitude = lat, longitude = lon,
            courseDegrees = if (courseObserved) estimated.headingDegrees else null,
            modelStdMetres = max(estimated.horizontalUncertaintyMetres, MODEL_GPS_STD_METRES),
            gpsReceiptGapMs = lastGapMs,
            travelledModelMetres = modelDistance,
            mode = lastMode,
            lastCarGpsAtMonoMs = lastGpsMonoMs
        )
        lastReportedModelStdMetres = next.modelStdMetres
        _current.value = next
        return next
    }

    private fun toLocal(lat0: Double, lon0: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val latRad = Math.toRadians(lat0)
        val e2 = 6.6943799901413165e-3
        val den = sqrt(1.0 - e2 * sin(latRad) * sin(latRad))
        val re = 6378137.0 / den
        val rn = 6378137.0 * (1.0 - e2) / (den * den * den)
        return Pair((lon - lon0) * PI / 180.0 * re * cos(latRad),
            (lat - lat0) * PI / 180.0 * rn)
    }

    @Synchronized fun evidence(): Evidence = Evidence(
        mode = lastMode, gpsAccepted = gpsCount, gearAccepted = gearCount,
        predictedForwardIntervals = predictedCount,
        noHeadingOrDirectionIntervals = heldCount, gearTimeGaps = discontinuityCount,
        lastGpsReceiptGapMs = lastGapMs,
        lastModelStdMetres = lastReportedModelStdMetres,
        travelledModelMetres = modelDistance, active = active
    )
}

/** One session-scoped estimator, separate from verified OEM DR authority. */
internal object CarTunnelRuntime {
    private val model = CarTunnelKalman()
    val current: StateFlow<CarTunnelKalman.Estimate?> = model.current
    fun beginSession() = model.beginSession()
    fun endSession() = model.endSession()
    fun onGps(sample: VehicleLocation, receiptMonoMs: Long) = model.onGps(sample, receiptMonoMs)
    fun onGear(speedKmh: Long?, gearRaw: Long?, receiptMonoMs: Long) =
        model.onGear(speedKmh, gearRaw, receiptMonoMs)
    fun evidence(): String = model.evidence().reportLine()
}
