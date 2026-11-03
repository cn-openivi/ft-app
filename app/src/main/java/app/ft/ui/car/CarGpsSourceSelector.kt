package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.GpsSourceMode
import app.ft.core.VehicleLocation

/**
 * Hard GPS source selection, shared with the owner-approved CAR/PHONE setting.
 *
 * No automatic switching to the handset when the car loses satellites,
 * position frames or trusted coordinates. The handset is *never* a hidden
 * input to CAR/DR. When the source is PHONE, no car position or speed is used.
 * Future CAR-only TinyEKF may supply a separately labelled estimated position
 * without changing this origin policy.
 */
internal class CarGpsSourceSelector {
    enum class Source { ICCOA, PHONE }

    data class Reading(
        val source: Source,
        val car: VehicleLocation?,
        val phone: CarInfo.PhoneGpsFix?,
        val lastPacketAgeMs: Long,
        val speedKmh: Float?
    ) {
        val latitude: Double? get() = if (source == Source.ICCOA)
            car?.displayLatitude else phone?.latitude
        val longitude: Double? get() = if (source == Source.ICCOA)
            car?.displayLongitude else phone?.longitude
        val headingDegrees: Float? get() = if (source == Source.ICCOA)
            car?.headingDegrees else phone?.bearingDegrees
        val label: String get() = source.name
    }

    private var lastCarPositionAtMs = Long.MIN_VALUE
    private var lastCarFix: VehicleLocation? = null
    private var carSpeedFilteredKmh: Float? = null

    fun select(
        vehicle: VehicleLocation?,
        phone: CarInfo.PhoneGpsFix?,
        nowMs: Long,
        mode: GpsSourceMode
    ): Reading = when (mode) {
        GpsSourceMode.PHONE -> {
            val validPhone = phone?.takeIf {
                it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                it.longitude.isFinite() && it.longitude in -180.0..180.0
            }
            Reading(
                Source.PHONE, null, validPhone,
                (nowMs - (validPhone?.receivedAtMs ?: nowMs)).coerceAtLeast(0L),
                validPhone?.speedKmh?.takeIf { it.isFinite() && it in 0f..300f }
            )
        }

        GpsSourceMode.CAR -> {
            val candidate = vehicle?.takeIf {
                it.source == "ICCOA" && it.positionAtMs > 0L &&
                it.displayLatitude != null && it.displayLongitude != null
            }
            if (candidate != null && candidate.positionAtMs > lastCarPositionAtMs) {
                val ageMs = nowMs - candidate.positionAtMs
                // A new screen can read an old position cache; do not make
                // that stale cache a fresh trusted measurement.
                if (ageMs in -1_000L..3_500L) {
                    lastCarPositionAtMs = candidate.positionAtMs
                    lastCarFix = candidate
                    val speed = candidate.speedKmh
                        ?.takeIf { it.isFinite() && it in 0f..300f }
                    if (speed != null) {
                        // Widget-only smoothing; never used as DR wheel speed.
                        carSpeedFilteredKmh = carSpeedFilteredKmh
                            ?.let { it * 0.55f + speed * 0.45f } ?: speed
                    }
                }
            }
            Reading(
                Source.ICCOA, lastCarFix, null,
                lastCarFix?.let { (nowMs - it.positionAtMs).coerceAtLeast(0L) } ?: 0L,
                carSpeedFilteredKmh
            )
        }
    }
}
