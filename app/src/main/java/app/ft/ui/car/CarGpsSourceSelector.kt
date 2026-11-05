package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.VehicleLocation

/**
 * FT widget source hysteresis, independent of the Android Auto sensor channel.
 *
 * The car has priority. Do NOT fall back because a GPS coordinate is >3s old,
 * and do NOT allow GearInfo traffic to masquerade as a new GPS fix.
 * Change source only after a sustained 7s GPS gap / 7s recovery.
 * This is the FT on-screen source selector, independent of Android Auto's
 * explicit LOCATION subscription and the TinyEKF GNSS-reference gate.
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
        val headingDegrees: Float? get() = (if (source == Source.ICCOA)
            car?.headingDegrees else phone?.bearingDegrees)
            ?.takeIf { it.isFinite() && it >= 0f && it <= 360f }
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
        // Expire a full OEM GPS outage BEFORE consuming a newly arriving fix.
        // Otherwise the first packet at the 7s boundary resets lastCarPacketAtMs
        // and conceals the outage, incorrectly retaining ICCOA as the source.
        if (source == Source.ICCOA && lastCarPacketAtMs?.let {
                nowMs - it >= switchAfterMs
            } == true) {
            source = Source.PHONE
        }
        if (candidate != null && candidate.positionAtMs > lastObservedCarPositionMs) {
            // A reordered OEM packet must not lower the high-water mark:
            // otherwise a second stale packet could look newly accepted.
            // Newly mounted screens may see an old stored fix. That is not
            // evidence of live telemetry, even though it can be displayed.
            val age = nowMs - candidate.positionAtMs
            if (age >= -1_000L && age <= maxRecoveryGapMs) {
                // Advance the high-water mark only for an accepted fix.
                // A rejected future timestamp must not poison subsequent
                // valid OEM positions or delay the 7s recovery timer.
                lastObservedCarPositionMs = candidate.positionAtMs
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
                } else {
                    // A new valid position without a valid speed must not
                    // reuse a speed measured at an older GPS position.
                    carSpeedFilteredKmh = null
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
        // Preserve the defensive PHONE input validation from the newer
        // source-only branch when restoring FT174's car-primary hysteresis.
        // A corrupted/stale Android coordinate must not replace a lost OEM fix.
        val safePhone = phone?.takeIf {
            // The fallback is valid only if the handset itself has a recent
            // GNSS measurement. Receiving a cached Location callback now
            // does not make its old elapsedRealtime fix physically current.
            val callbackAge = nowMs - it.receivedAtMs
            val fixAge = it.fixAgeNanosAtReceipt
            val callbackFresh = callbackAge in 0L..switchAfterMs
            // Age at receipt and age since receipt are cumulative. Checking
            // each against 7s separately could admit a 12s-old GNSS fix.
            // Use a remaining nanosecond budget to avoid rounding at the edge.
            val originalFixFresh = fixAge == null || (callbackFresh &&
                fixAge >= 0L &&
                fixAge <= (switchAfterMs - callbackAge) * 1_000_000L)
            it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                it.longitude.isFinite() && it.longitude in -180.0..180.0 &&
                callbackFresh && originalFixFresh
        }
        val carChosen = source == Source.ICCOA && lastCarFix != null
        return if (carChosen) {
            Reading(Source.ICCOA, lastCarFix, phone,
                (nowMs - (lastCar ?: nowMs)).coerceAtLeast(0L), carSpeedFilteredKmh)
        } else {
            Reading(Source.PHONE, null, safePhone,
                safePhone?.let { fix ->
                    val callbackAgeMs = (nowMs - fix.receivedAtMs).coerceAtLeast(0L)
                    val fixAgeMs = fix.fixAgeNanosAtReceipt?.let { nanos ->
                        // Round up: never display a sub-millisecond stale fix as newer.
                        nanos / 1_000_000L + if (nanos % 1_000_000L == 0L) 0L else 1L
                    } ?: 0L
                    callbackAgeMs + fixAgeMs
                } ?: 0L,
                safePhone?.speedKmh?.takeIf { it.isFinite() && it in 0f..300f })
        }
    }
}
