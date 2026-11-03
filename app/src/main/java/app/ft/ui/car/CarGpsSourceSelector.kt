package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.VehicleLocation

/**
 * FT widget source hysteresis, independent of the Android Auto sensor channel.
 *
 * The car has priority. Do NOT fall back because a GPS coordinate is >3s old,
 * and do NOT allow GearInfo traffic to masquerade as a new GPS fix.
 * Change source only after a sustained 7s GPS gap / 7s recovery.
 */
internal class CarGpsSourceSelector(
    private val switchAfterMs: Long = 7_000L,
    private val maxRecoveryGapMs: Long = 3_500L
) {
    init {
        require(switchAfterMs in 5_000L..10_000L)
        require(maxRecoveryGapMs in 1_000L until switchAfterMs)
    }

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

    private var source = Source.PHONE
    private var lastObservedCarPositionMs = Long.MIN_VALUE
    private var lastCarPacketAtMs: Long? = null
    private var stableCarSinceMs: Long? = null
    private var lastCarFix: VehicleLocation? = null
    private var carSpeedFilteredKmh: Float? = null

    fun select(
        vehicle: VehicleLocation?,
        phone: CarInfo.PhoneGpsFix?,
        nowMs: Long
    ): Reading {
        val candidate = vehicle?.takeIf {
            it.source == "ICCOA" && it.positionAtMs > 0L &&
                it.displayLatitude != null && it.displayLongitude != null
        }
        if (candidate != null && candidate.positionAtMs != lastObservedCarPositionMs) {
            val previousPosition = lastObservedCarPositionMs
            lastObservedCarPositionMs = candidate.positionAtMs
            // Newly mounted screens may see an old stored fix. That is not
            // evidence of live telemetry, even though it can be displayed.
            val age = nowMs - candidate.positionAtMs
            if (candidate.positionAtMs > previousPosition &&
                age >= -1_000L && age <= maxRecoveryGapMs) {
                val last = lastCarPacketAtMs
                if (last == null || nowMs < last || nowMs - last > maxRecoveryGapMs) {
                    stableCarSinceMs = nowMs
                    carSpeedFilteredKmh = null
                }
                lastCarPacketAtMs = nowMs
                lastCarFix = candidate
                val speed = candidate.speedKmh?.takeIf { it.isFinite() && it in 0f..300f }
                if (speed != null) {
                    // OEM Location.getSpeed was floored to integer m/s;
                    // smooth only the widget display, never AA wire data.
                    carSpeedFilteredKmh = carSpeedFilteredKmh?.let {
                        it * 0.55f + speed * 0.45f
                    } ?: speed
                }
            }
        }

        val lastCar = lastCarPacketAtMs
        when (source) {
            Source.ICCOA ->
                if (lastCar == null || nowMs - lastCar >= switchAfterMs)
                    source = Source.PHONE
            Source.PHONE ->
                if (lastCar != null && stableCarSinceMs != null &&
                    nowMs - lastCar <= maxRecoveryGapMs &&
                    nowMs - stableCarSinceMs!! >= switchAfterMs)
                    source = Source.ICCOA
        }
        val carChosen = source == Source.ICCOA && lastCarFix != null
        return if (carChosen) {
            Reading(Source.ICCOA, lastCarFix, phone,
                (nowMs - (lastCar ?: nowMs)).coerceAtLeast(0L), carSpeedFilteredKmh)
        } else {
            Reading(Source.PHONE, null, phone,
                (nowMs - (phone?.receivedAtMs ?: nowMs)).coerceAtLeast(0L),
                phone?.speedKmh?.takeIf { it.isFinite() && it in 0f..300f })
        }
    }
}
