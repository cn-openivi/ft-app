package app.ft.dr

import app.ft.core.GpsSourceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Verified 2D TinyEKF -> geographic WGS84 adapter.
 *
 * This is NOT a mock LocationManager provider and never modifies the
 * handset's GPS. A future protocol adapter must supply the actual GNSS
 * reference point, validated OEM wheel/yaw units and timestamps.
 */
internal data class DrNavigationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Double,
    val headingDegrees: Double,
    val fromSelectedGps: GpsSourceMode,
    val producedAtMs: Long,
    val estimated: Boolean,
    val verifiedVehicleMotion: Boolean,
    val speedKmh: Float? = null
) {
    // FT is the positioning owner. AA inherits these validity requirements,
    // never the reverse. This bounded frame-age rejects cached/replayed DR,
    // it is NOT a 1.5s GNSS-loss timer or a source-switch threshold.
    fun eligibleForFt(nowMs: Long): Boolean =
        fromSelectedGps == GpsSourceMode.CAR && verifiedVehicleMotion &&
            producedAtMs > 0L && nowMs >= producedAtMs &&
            nowMs - producedAtMs <= 1_500L &&
            latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            accuracyMetres.isFinite() && accuracyMetres >= 0.0 &&
            headingDegrees.isFinite()

    /** AA remains a downstream subscriber of FT's validated DR model. */
    fun eligibleForAa(nowMs: Long): Boolean = eligibleForFt(nowMs)
}

/** FT's verified EKF prediction/measurement publication, shared by all consumers. */
internal object DrNavigationTelemetry {
    private val _current = MutableStateFlow<DrNavigationFix?>(null)
    val current: StateFlow<DrNavigationFix?> = _current
    // Native FT reads current and new events; AA merely subscribes to FT's
    // central bus. No replay of a stale GNSS or DR position.
    private val _updates = MutableSharedFlow<DrNavigationFix>(
        replay = 0, extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates: kotlinx.coroutines.flow.SharedFlow<DrNavigationFix> = _updates
    @Synchronized
    fun publishFromVerifiedEkf(
        referenceLatitude: Double,
        referenceLongitude: Double,
        estimate: DeadReckoningEkf.Estimate,
        gpsSourceMode: GpsSourceMode,
        wheelSpeedAndYawVerified: Boolean,
        producedAtMs: Long
    ): Boolean {
        if (!wheelSpeedAndYawVerified ||
            !referenceLatitude.isFinite() ||
            !referenceLongitude.isFinite() || referenceLatitude !in -85.0..85.0 ||
            referenceLongitude !in -180.0..180.0 ||
            !estimate.eastMetres.isFinite() || !estimate.northMetres.isFinite() ||
            !estimate.horizontalUncertaintyMetres.isFinite() ||
            estimate.horizontalUncertaintyMetres < 0.0 ||
            !estimate.headingDegrees.isFinite() || producedAtMs <= 0L) return false

        // WGS84 ellipsoidal local ENU metres -> geographic position. The same
        // anchor must remain fixed until a deliberate origin reset.
        val latRad = Math.toRadians(referenceLatitude)
        val e2 = 6.6943799901413165e-3
        val sinLat = kotlin.math.sin(latRad)
        val den = sqrt(1.0 - e2 * sinLat * sinLat)
        val eastRadius = 6378137.0 / den
        val northRadius = 6378137.0 * (1.0 - e2) / (den * den * den)
        val lat = referenceLatitude +
            estimate.northMetres / northRadius * 180.0 / PI
        val lon = referenceLongitude +
            estimate.eastMetres / (eastRadius * cos(latRad)) * 180.0 / PI
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
            lon !in -180.0..180.0) return false
        val next = DrNavigationFix(
            latitude = lat, longitude = lon,
            accuracyMetres = estimate.horizontalUncertaintyMetres,
            headingDegrees = estimate.headingDegrees,
            fromSelectedGps = gpsSourceMode,
            producedAtMs = producedAtMs,
            estimated = estimate.estimated,
            verifiedVehicleMotion = true,
            speedKmh = estimate.wheelSpeedMps?.takeIf { it.isFinite() }
                ?.let { kotlin.math.abs(it) * 3.6 }
                ?.takeIf { it in 0.0..300.0 }?.toFloat()
        )
        _current.value = next
        _updates.tryEmit(next)
        return true
    }

    @Synchronized
    fun clear() {
        _current.value = null
    }
}
