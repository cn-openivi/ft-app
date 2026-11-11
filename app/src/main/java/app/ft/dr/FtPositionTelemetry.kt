package app.ft.dr

import app.ft.core.VehicleLocation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * FT's authoritative position feed. Android Auto is ONE consumer alongside
 * the phone FT dashboard, widgets and future FT native navigation apps.
 *
 * OEM GNSS feeds the fusion engine as measurements, not a parallel location
 * feed when verified GNSS+wheel/yaw TinyEKF is active. Fused authority never
 * silently switches to raw multipath-prone OEM points during a GNSS gap.
 *
 * Until independently verified car motion/accuracy is integrated, RAW_UNFILTERED
 * is a truthful baseline; it MUST NOT be advertised as DR or multipath safe.
 */
internal data class FtNavigationPosition(
    val source: Source,
    val receivedAtMs: Long,
    val raw: VehicleLocation? = null,
    val fused: DrNavigationFix? = null
) {
    enum class Source { RAW_UNFILTERED, FUSED }

    val latitude: Double? get() =
        if (source == Source.FUSED) fused?.latitude else raw?.displayLatitude
    val longitude: Double? get() =
        if (source == Source.FUSED) fused?.longitude else raw?.displayLongitude
    val headingDegrees: Float? get() =
        if (source == Source.FUSED) fused?.headingDegrees?.toFloat()
        else raw?.headingDegrees
    val horizontalAccuracyMetres: Double? get() =
        if (source == Source.FUSED) fused?.accuracyMetres else null

    fun eligibleForDisplay(nowMs: Long): Boolean =
        when (source) {
            Source.FUSED -> fused?.eligibleForFt(nowMs) == true
            Source.RAW_UNFILTERED ->
                raw?.source == "ICCOA" && receivedAtMs > 0L &&
                    nowMs >= receivedAtMs &&
                    latitude?.isFinite() == true &&
                    longitude?.isFinite() == true
        }
}

/**
 * Single in-process FT navigation distribution point. All consumers share
 * the exact same provenance choice, not separate local GPS/DR heuristics.
 * A live fused prediction has priority regardless of the last raw GNSS time.
 */
internal object FtPositionTelemetry {
    private val _current = MutableStateFlow<FtNavigationPosition?>(null)
    val current: StateFlow<FtNavigationPosition?> = _current

    private val _updates = MutableSharedFlow<FtNavigationPosition>(
        replay = 0, extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val updates: SharedFlow<FtNavigationPosition> = _updates

    private var fusedAuthority = false
    private var lastRawSequence = 0L
    private var lastPositionAtMs = Long.MIN_VALUE

    @Synchronized
    fun acceptRaw(sample: VehicleLocation, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (fusedAuthority || sample.source != "ICCOA" ||
            sample.coordinatesUnit != "ANDROID_LOCATION_WGS84" ||
            sample.positionAtMs <= 0L || sample.positionAtMs > nowMs ||
            sample.positionAtMs < lastPositionAtMs) return false
        val lat = sample.displayLatitude
        val lon = sample.displayLongitude
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            (lat == 0.0 && lon == 0.0)) return false

        val seq = sample.gpsPacketSequence
        if (seq > 0L && seq <= lastRawSequence) return false
        if (seq <= 0L && sample.positionAtMs <= lastPositionAtMs) return false

        val next = FtNavigationPosition(
            source = FtNavigationPosition.Source.RAW_UNFILTERED,
            receivedAtMs = sample.positionAtMs, raw = sample)
        if (seq > 0L) lastRawSequence = seq
        lastPositionAtMs = sample.positionAtMs
        _current.value = next
        _updates.tryEmit(next)
        return true
    }

    @Synchronized
    fun acceptFused(fix: DrNavigationFix, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!fix.eligibleForFt(nowMs) ||
            fix.producedAtMs < lastPositionAtMs ||
            (fusedAuthority && fix.producedAtMs <= lastPositionAtMs)) return false
        fusedAuthority = true
        val next = FtNavigationPosition(
            source = FtNavigationPosition.Source.FUSED,
            receivedAtMs = fix.producedAtMs, fused = fix)
        lastPositionAtMs = fix.producedAtMs
        _current.value = next
        _updates.tryEmit(next)
        return true
    }

    /** Only a verified explicit disconnection/reset can relinquish fusion. */
    @Synchronized
    fun clear() {
        fusedAuthority = false
        lastRawSequence = 0L
        lastPositionAtMs = Long.MIN_VALUE
        _current.value = null
    }
}
