package app.ft.aa

import app.ft.core.ProtoWriter
import app.ft.core.VehicleLocation
import app.ft.dr.DrNavigationFix
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Android Auto SENSOR_LOCATION bridge for verified decimal-degree car fixes.
 *
 * The source must explicitly declare Android Location API / WGS84 provenance.
 * Baidu integer GPS is NOT forwarded until its scale/CRS is verified.
 * No guessed speed, bearing, accuracy, altitude or Dead Reckoning flags are sent.
 */
internal object AaCarGps {
    const val LOCATION = AaProtocol.SENSOR_LOCATION
    private const val CRS = "ANDROID_LOCATION_WGS84"

    fun event(location: VehicleLocation?, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (location == null || location.source != "ICCOA" || location.coordinatesUnit != CRS ||
            !location.positionFresh(nowMs)) return null
        val latitude = location.displayLatitude ?: return null
        val longitude = location.displayLongitude ?: return null
        // An uninitialized OEM fix often uses Null Island: never send it to AA Maps.
        if (latitude == 0.0 && longitude == 0.0) return null
        // Android Auto GPSLocationData: repeated field 1; sub-fields 2 & 3 are required
        // int32 microdegrees, NOT ICCOA's double or Baidu's raw int32.
        val latMicro = (latitude * 1_000_000.0).roundToInt()
        val lonMicro = (longitude * 1_000_000.0).roundToInt()
        // Protobuf int32 negative values are sign-extended to a 64-bit
        // two's-complement varint (10 bytes). The shared ProtoWriter.int32()
        // currently truncates to uint32 (5 bytes), which is not the canonical
        // representation for western/southern hemisphere coordinates.
        // Isolate this encoding correction to AA GPS so other OEM protocols
        // retain their existing wire behavior.
        return ProtoWriter().message(1, ProtoWriter()
            .varint(2, latMicro.toLong())
            .varint(3, lonMicro.toLong())
        ).toByteArray()
    }

    /**
     * FUSED WGS84 (not RAW_GNSS). Only for sessions that announced
     * LocationCharacterization.DEAD_RECKONED and received an explicit
     * SensorRequest(LOCATION). TinyEKF source and wheel/yaw units must be
     * independently validated before DrNavigationTelemetry can supply it.
     *
     * Android Auto GPSLocation submessage fields 2,3 are signed int32
     * microdegrees and optional field 4 is unsigned horizontal accuracy
     * in metres. The wire has NO "estimated" flag, so characterization
     * and conservative accuracy are mandatory.
     */
    fun fusedEvent(
        estimate: DrNavigationFix?,
        nowMs: Long,
        sessionCharacterization: Int
    ): ByteArray? {
        if (estimate == null || !estimate.eligibleForAa(nowMs) ||
            !AaLocationCharacterization.permitsEstimatedPosition(
                sessionCharacterization)) return null
        val accuracy = ceil(estimate.accuracyMetres)
            .coerceAtLeast(1.0)
        if (!accuracy.isFinite() || accuracy > Int.MAX_VALUE) return null
        if (estimate.latitude == 0.0 && estimate.longitude == 0.0) return null
        val lat = (estimate.latitude * 1_000_000.0).roundToInt()
        val lon = (estimate.longitude * 1_000_000.0).roundToInt()
        return ProtoWriter().message(1, ProtoWriter()
            .varint(2, lat.toLong())
            .varint(3, lon.toLong())
            .uint32(4, accuracy.toInt())
        ).toByteArray()
    }

    fun sensorError(status: Int): ByteArray =
        ProtoWriter().enum(1, LOCATION).enum(2, status).toByteArray()
}


/**
 * Suppress isolated GNSS jumps (including RF interference) before feeding maps.
 * Require three consecutive consistent distinct car fixes to acquire/reacquire.
 * Output and source selection remain independent of diagnostic 10-second sampling.
 */
internal class AaGpsGate {
    private var previous: VehicleLocation? = null
    // The last fix actually delivered to AA is the trusted movement anchor.
    // A self-consistent cluster of erroneous fixes must not move this anchor.
    private var lastDelivered: VehicleLocation? = null
    private var lastObservedAt = Long.MIN_VALUE
    private var consecutive = 0

    fun accept(sample: VehicleLocation?, nowMs: Long): ByteArray? {
        val preceding = previous
        // Polling may run at 100 ms while the OEM GNSS source updates only
        // once per second. Skip unchanged, still-fresh fixes BEFORE allocating
        // the nested LOCATION protobuf. Preserve the existing stale/reset path.
        if (preceding != null && sample != null &&
            sample.positionAtMs == preceding.positionAtMs &&
            sample.source == preceding.source &&
            sample.coordinatesUnit == preceding.coordinatesUnit &&
            sample.latitude == preceding.latitude &&
            sample.longitude == preceding.longitude &&
            sample.positionFresh(nowMs)) return null
        val encoded = AaCarGps.event(sample, nowMs)
        if (encoded == null || sample == null) {
            if (previous != null && nowMs - lastObservedAt > 3_000L) {
                // An RF outage is not consent to discard our last *delivered*
                // trusted location. Otherwise a spoofed post-tunnel cluster
                // can re-anchor AA merely by sending three consistent fixes.
                restartCandidateStream()
            }
            return null
        }
        if (preceding != null && preceding.positionAtMs == sample.positionAtMs) return null
        if (preceding != null) {
            val elapsedMs = sample.positionAtMs - preceding.positionAtMs
            val lat1 = preceding.displayLatitude
            val lon1 = preceding.displayLongitude
            val lat2 = sample.displayLatitude
            val lon2 = sample.displayLongitude
            if (elapsedMs !in 1L..3_000L || lat1 == null || lon1 == null || lat2 == null || lon2 == null ||
                unreasonableJump(lat1, lon1, lat2, lon2, elapsedMs)) {
                // A real gap resets the *candidate stream*, not the
                // last trusted position. Reacquisition must still satisfy
                // the plausible displacement bound from lastDelivered.
                previous = sample
                consecutive = 1
                lastObservedAt = nowMs
                return null
            }
        }
        previous = sample
        lastObservedAt = nowMs
        consecutive++
        if (consecutive < 3) return null
        val anchor = lastDelivered
        if (anchor != null) {
            val elapsedMs = sample.positionAtMs - anchor.positionAtMs
            val lat1 = anchor.displayLatitude ?: return null
            val lon1 = anchor.displayLongitude ?: return null
            val lat2 = sample.displayLatitude ?: return null
            val lon2 = sample.displayLongitude ?: return null
            // Guard against a sustained false GPS cluster, not just a single spike.
            // The stream must genuinely go stale before a far-away fix can reacquire.
            // The preceding-sample check already handles a genuine >3s source
            // gap. Do not permanently freeze AA after >3s of quarantined fixes:
            // compare distance to the last trusted fix over the real elapsed time.
            if (elapsedMs <= 0L ||
                unreasonableJump(lat1, lon1, lat2, lon2, elapsedMs)) return null
        }
        lastDelivered = sample
        return encoded
    }

    private fun restartCandidateStream() {
        previous = null
        consecutive = 0
        lastObservedAt = Long.MIN_VALUE
        // Deliberately preserve lastDelivered across GNSS outages.
    }

    fun reset() {
        restartCandidateStream()
        lastDelivered = null
    }

    private fun unreasonableJump(lat1: Double, lon1: Double, lat2: Double, lon2: Double, ms: Long): Boolean {
        val dy = Math.toRadians(lat2 - lat1)
        val dx = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dy / 2) * kotlin.math.sin(dy / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dx / 2) * kotlin.math.sin(dx / 2)
        val meters = 2.0 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
        // Allow up to 300 km/h plus 30 m of GNSS jitter between readings.
        return meters > 30.0 + (ms / 1_000.0) * (300.0 / 3.6)
    }
}
