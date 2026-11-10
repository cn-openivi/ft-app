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
 * Transfer all fields that the AA LOCATION schema supports, but never fabricate accuracy or DR.
 * Preserve other OEM metadata (antenna/PDOP/satellite counts) in VehicleTelemetry; GPS
 * satellite counts are a separate opt-in AA sensor, not fields of LOCATION.
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
        // int32 degrees × 1e7 (latitude_e7/longitude_e7), NOT ICCOA doubles or Baidu raw ints.
        val latE7 = (latitude * 10_000_000.0).roundToInt()
        val lonE7 = (longitude * 10_000_000.0).roundToInt()
        // Protobuf int32 negative values are sign-extended to a 64-bit
        // two's-complement varint (10 bytes). The shared ProtoWriter.int32()
        // currently truncates to uint32 (5 bytes), which is not the canonical
        // representation for western/southern hemisphere coordinates.
        // Isolate this encoding correction to AA GPS so other OEM protocols
        // retain their existing wire behavior.
        val gps = ProtoWriter()
            .varint(2, latE7.toLong())
            .varint(3, lonE7.toLong())
        // FAW CarLinkSensorMgr floors Android Location.getSpeed() (metres/s)
        // and Location.getBearing() (degrees) before ICCOA Gps field 6/7.
        // AA LocationData uses OPTIONAL signed speed_e3 field 6 (m/s * 1000)
        // and bearing_e6 field 7 (clockwise degrees * 1e6). Only publish
        // original OEM GPS metadata with explicitly verified provenance:
        // GearInfo speed, phone GNSS and guessed heading are never mixed in.
        val altitude = location.altitudeMeters
        if (location.altitudeUnit == "ANDROID_LOCATION_METRES" &&
            altitude != null && altitude.isFinite()) {
            val altitudeE2 = altitude * 100.0
            if (altitudeE2.isFinite() && altitudeE2 >= Int.MIN_VALUE &&
                altitudeE2 <= Int.MAX_VALUE) {
                // Signed int32 negative altitude must be encoded as a 10-byte varint.
                gps.varint(5, altitudeE2.roundToInt().toLong())
            }
        }
        val speed = location.speedRaw
        if (location.speedUnit == "ANDROID_LOCATION_MPS_FLOORED" &&
            speed != null && speed in 0L..125L) {
            gps.int32(6, (speed * 1_000L).toInt())
        }
        val bearing = location.headingRaw
        // Course over ground is undefined at a standstill. FAW CarLink
        // floors both speed and bearing; a raw zero at zero speed is not
        // evidence that the car is physically pointing north. Preserve
        // genuine moving zero-degree north without modifying raw diagnostics.
        if (location.headingUnit == "ANDROID_LOCATION_DEGREES_FLOORED" &&
            location.speedUnit == "ANDROID_LOCATION_MPS_FLOORED" &&
            speed != null && speed in 1L..125L &&
            bearing != null && bearing in 0L..359L) {
            gps.int32(7, (bearing * 1_000_000L).toInt())
        }
        return ProtoWriter().message(1, gps).toByteArray()
    }

    /**
     * FUSED WGS84 (not RAW_GNSS). Only for sessions that announced
     * LocationCharacterization.DEAD_RECKONED and received an explicit
     * SensorRequest(LOCATION). TinyEKF source and wheel/yaw units must be
     * independently validated before DrNavigationTelemetry can supply it.
     *
     * Android Auto GPSLocation submessage fields 2,3 are signed int32
     * degrees × 1e7 and optional field 4 is unsigned horizontal accuracy
     * in millimetres (accuracy_e3). The wire has NO "estimated" flag, so characterization
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
        val accuracyE3 = ceil(estimate.accuracyMetres * 1_000.0)
            .coerceAtLeast(1_000.0)
        if (!accuracyE3.isFinite() || accuracyE3 > Int.MAX_VALUE) return null
        if (estimate.latitude == 0.0 && estimate.longitude == 0.0) return null
        val lat = (estimate.latitude * 10_000_000.0).roundToInt()
        val lon = (estimate.longitude * 10_000_000.0).roundToInt()
        return ProtoWriter().message(1, ProtoWriter()
            .varint(2, lat.toLong())
            .varint(3, lon.toLong())
            .uint32(4, accuracyE3.toInt())
        ).toByteArray()
    }

    /**
     * Android Auto sensor 21 (GPS_SATELLITES) supports aggregate counts only from
     * this OEM source. Per-satellite PRN/SNR/azimuth/elevation is NOT present in Carlink Gps.
     * SensorBatch field 21 -> GpsSatelliteData { satellite_count=1; satellites_used=2 }.
     */
    fun satellitesEvent(location: VehicleLocation?, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (location == null || location.source != "ICCOA" ||
            location.coordinatesUnit != CRS || !location.positionFresh(nowMs) ||
            location.displayLatitude == null || location.displayLongitude == null ||
            (location.displayLatitude == 0.0 && location.displayLongitude == 0.0)) return null
        val view = location.satellitesVisible ?: return null
        val used = location.satellitesUsed ?: return null
        if (view !in 0L..255L || used !in 0L..255L) return null
        return ProtoWriter().message(21,
            ProtoWriter().int32(1, view.toInt()).int32(2, used.toInt())).toByteArray()
    }

    fun sensorError(status: Int): ByteArray =
        ProtoWriter().enum(1, LOCATION).enum(2, status).toByteArray()
}


/**
 * Owner-approved 7-second AA OEM GNSS loss window.
 *
 * This tracks actual accepted location-event delivery, not GearInfo receipt,
 * satellite counters, polling frequency or an inferred phone subscription.
 * Its clock is Android elapsedRealtime, independent of wall-clock changes.
 * AA alone decides whether it resumes phone GPS after a sensor error.
 */
internal class AaGpsAvailabilityGate(
    startedElapsedMs: Long,
    private val lossAfterMs: Long = 7_000L
) {
    init { require(lossAfterMs in 5_000L..10_000L) }

    private var lastDeliveredElapsedMs = startedElapsedMs
    private var unavailable = false

    /** True exactly when a previously reported source outage has recovered. */
    fun onDeliveredLocation(elapsedMs: Long): Boolean {
        val recovered = unavailable
        unavailable = false
        lastDeliveredElapsedMs = elapsedMs
        return recovered
    }

    /** Report one outage only after a full 7s without accepted OEM GPS. */
    fun shouldReportUnavailable(elapsedMs: Long): Boolean {
        if (unavailable || elapsedMs < lastDeliveredElapsedMs ||
            elapsedMs - lastDeliveredElapsedMs < lossAfterMs) return false
        unavailable = true
        return true
    }
}

/**
 * Suppress isolated GNSS jumps (including RF interference) before feeding maps.
 * Require three consecutive consistent distinct car fixes to acquire/reacquire.
 * Output and source selection remain independent of diagnostic 10-second sampling.
 */
internal class AaGpsGate {
    private var lastDeliveredPositionAtMs = Long.MIN_VALUE

    /**
     * Match open-headunit's first-fix delivery: the first fresh OEM location is
     * sent immediately after Android Auto subscribes. Only reject unavailable,
     * stale or malformed samples and old/duplicate measurements. No 3-fix
     * acquisition, distance heuristics, synthetic accuracy, or unsolicited AA data.
     */
    fun accept(sample: VehicleLocation?, nowMs: Long): ByteArray? {
        val packet = AaCarGps.event(sample, nowMs) ?: return null
        val positionAt = sample!!.positionAtMs
        if (positionAt <= lastDeliveredPositionAtMs) return null
        lastDeliveredPositionAtMs = positionAt
        return packet
    }

    fun reset() { lastDeliveredPositionAtMs = Long.MIN_VALUE }
}
