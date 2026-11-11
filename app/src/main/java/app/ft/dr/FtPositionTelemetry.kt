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

    // A last-known observation is displayable after a handset clock change.
    // The caller must label its age; this does not attest live GPS.
    fun eligibleForDisplay(nowMs: Long): Boolean =
        when (source) {
            Source.FUSED -> fused?.eligibleForFt(nowMs) == true
            Source.RAW_UNFILTERED ->
                raw?.source == "ICCOA" && receivedAtMs > 0L &&
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
    @Synchronized
    fun acceptRaw(sample: VehicleLocation, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (fusedAuthority || sample.source != "ICCOA" ||
            sample.coordinatesUnit != "ANDROID_LOCATION_WGS84" ||
            sample.receivedAtMs <= 0L) return false
        val lat = sample.displayLatitude
        val lon = sample.displayLongitude
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            (lat == 0.0 && lon == 0.0)) return false

        val next = FtNavigationPosition(
            source = FtNavigationPosition.Source.RAW_UNFILTERED,
            receivedAtMs = sample.positionAtMs, raw = sample)
        _current.value = next
        _updates.tryEmit(next)
        return true
    }

    @Synchronized
    fun acceptFused(fix: DrNavigationFix, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!fix.eligibleForFt(nowMs)) return false
        fusedAuthority = true
        val next = FtNavigationPosition(
            source = FtNavigationPosition.Source.FUSED,
            receivedAtMs = fix.producedAtMs, fused = fix)
        _current.value = next
        _updates.tryEmit(next)
        return true
    }

    /** Only a verified explicit disconnection/reset can relinquish fusion. */
    @Synchronized
    fun clear() {
        fusedAuthority = false
        _current.value = null
    }
}
