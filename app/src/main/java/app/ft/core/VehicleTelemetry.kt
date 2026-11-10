package app.ft.core

import app.ft.FTApp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared, protocol-neutral vehicle sensors for FT widgets and later AA location forwarding.
 * No coordinate datum, speed multiplier or heading unit is guessed.
 */
data class VehicleLocation(
    val source: String,
    val receivedAtMs: Long,
    val positionAtMs: Long = receivedAtMs,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val rawLatitude: Long? = null,
    val rawLongitude: Long? = null,
    val speedRaw: Long? = null,
    // OEM GearInfo and OEM GPS speed are different raw units; never conflate them.
    val gearSpeedRaw: Long? = null,
    val gearRaw: Long? = null,
    // GearInfo can update speed without carrying a new gear enum. Never
    // refresh a physical gear's age from a speed-only packet.
    val gearAtMs: Long = 0L,
    val gearSpeedAtMs: Long = 0L,
    val headingRaw: Long? = null,
    val speedKmh: Float? = null,
    val headingDegrees: Float? = null,
    val satellitesUsed: Long? = null,
    val satellitesVisible: Long? = null,
    val deviceTimestampRaw: Long? = null,
    val coordinatesUnit: String = "UNKNOWN",
    val speedUnit: String = "UNKNOWN",
    val headingUnit: String = "UNKNOWN",
    // Exact GPS metadata provided by FAW Carlink/ICCOA; do not invent units for PDOP/antenna.
    val altitudeMeters: Double? = null,
    val altitudeUnit: String = "UNKNOWN",
    val antennaStateRaw: Long? = null,
    val pdopRaw: Long? = null
) {
    fun fresh(nowMs: Long = System.currentTimeMillis()) =
        nowMs >= receivedAtMs && nowMs - receivedAtMs <= 3_000L

    fun positionFresh(nowMs: Long = System.currentTimeMillis()) =
        positionAtMs > 0L && nowMs >= positionAtMs && nowMs - positionAtMs <= 3_000L

    /** OEM GearInfo speed, never a GNSS fix or proof of calibrated wheel speed. */
    fun gearSpeedFresh(nowMs: Long = System.currentTimeMillis()) =
        source == "ICCOA" && gearSpeedRaw != null && gearSpeedAtMs > 0L &&
            nowMs >= gearSpeedAtMs && nowMs - gearSpeedAtMs <= 3_000L

    val displayLatitude: Double? get() = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
    val displayLongitude: Double? get() = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
}

/** Both Baidu CarLife and ICCOA publish here. All consumers observe one stable state. */
object VehicleTelemetry {
    private val _current = MutableStateFlow<VehicleLocation?>(null)
    val current: StateFlow<VehicleLocation?> = _current
    private val logSampler = VehicleLogSampler()

    /**
     * 10s routine samples, temporary 1Hz bursts after source/fix changes or a telemetry gap.
     * Does not throttle real-time position delivery.
     */
    private fun note(sample: VehicleLocation) {
        val now = sample.receivedAtMs
        val hasFix = sample.positionFresh(now) &&
            ((sample.displayLatitude != null && sample.displayLongitude != null) ||
             (sample.rawLatitude != null && sample.rawLongitude != null))
        val decision = logSampler.accept(sample.source, now, hasFix) ?: return
        runCatching {
            DiagLog.i("VehicleGPS", VehicleCoordinateLog.format(
                sample, decision, FTApp.instance.prefs.vehicleCoordinateLogging,
                phoneGpsSpeedKmh = if (sample.source == "ICCOA" &&
                    (sample.gearSpeedRaw != null || sample.speedRaw != null))
                    CarInfo.freshPhoneSpeedKmh(now) else null
            ))
        }
    }

    @Synchronized fun publish(location: VehicleLocation) {
        require(location.source == "ICCOA" || location.source == "BAIDU")
        // A delayed GPS measurement may carry an older receivedAtMs than a
        // just-arrived GearInfo frame. Evaluate GearInfo freshness against
        // the local clock, never against the GPS packet's past timestamp.
        val previous = _current.value?.takeIf {
            it.source == location.source && it.fresh(System.currentTimeMillis())
        }
        // Late OEM GPS frames must not replace a newer accepted position.
        // GearInfo is updated independently by updateIccoaSpeed(), so an
        // older GPS frame must not roll back the shared telemetry snapshot.
        // Reject implausibly future-dated GPS positions before they can poison
        // the shared high-water mark. Match the selector's 1s clock tolerance.
        // GearInfo is delivered through updateIccoaSpeed(), not publish().
        if (location.positionAtMs > 0L &&
            location.positionAtMs > location.receivedAtMs &&
            location.positionAtMs - location.receivedAtMs > 1_000L) return
        val latestPosition = _current.value?.takeIf { it.source == location.source }
        if (latestPosition != null && latestPosition.positionAtMs > 0L &&
            (location.positionAtMs <= 0L ||
                latestPosition.positionAtMs >= location.positionAtMs)) return
        // A new GPS frame must not erase a recent, separately sourced GearInfo frame.
        val preserved = if (location.source == "ICCOA" && previous != null)
            location.copy(gearSpeedRaw = previous.gearSpeedRaw,
                gearRaw = previous.gearRaw, gearAtMs = previous.gearAtMs,
                gearSpeedAtMs = previous.gearSpeedAtMs) else location
        _current.value = preserved
        note(preserved)
    }

    @Synchronized fun updateBaiduSpeed(rawSpeed: Long, deviceTimestamp: Long? = null) {
        if (rawSpeed < 0L || rawSpeed > 100_000L) return
        val old = _current.value?.takeIf { it.source == "BAIDU" && it.fresh() }
        _current.value = (old ?: VehicleLocation("BAIDU", System.currentTimeMillis(), positionAtMs = 0L)).copy(
            receivedAtMs = System.currentTimeMillis(), speedRaw = rawSpeed,
            deviceTimestampRaw = deviceTimestamp ?: old?.deviceTimestampRaw
        )
        _current.value?.let(::note)
    }

    @Synchronized fun updateIccoaSpeed(rawSpeed: Long, gearRaw: Long? = null) {
        if (rawSpeed < 0L || rawSpeed > 100_000L || (gearRaw != null && gearRaw !in 0L..4L)) return
        val now = System.currentTimeMillis()
        // A new GearInfo frame must not erase the last GPS position after a
        // long gap. Freshness uses positionAtMs independently; retaining the
        // stale fix is for UI only and never makes it usable by Android Auto.
        val old = _current.value?.takeIf { it.source == "ICCOA" }
        _current.value = (old ?: VehicleLocation("ICCOA", now, positionAtMs = 0L)).copy(
            receivedAtMs = now,
            gearSpeedRaw = rawSpeed,
            gearSpeedAtMs = now,
            gearRaw = gearRaw ?: old?.gearRaw,
            gearAtMs = if (gearRaw != null) now else old?.gearAtMs ?: 0L,
            // Keep unknown GPS raw speed distinct from the received GearInfo integer.
            speedRaw = old?.speedRaw
        )
        _current.value?.let(::note)
    }

    @Synchronized fun clear(source: String) {
        if (_current.value?.source == source) {
            _current.value = null
            runCatching { DiagLog.i("VehicleGPS", "reason=SOURCE_DISCONNECTED protocol=$source") }
            logSampler.reset()
        }
    }
}
