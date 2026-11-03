package app.ft.ui.car

/**
 * One visual GPS source for the car launcher. OEM packets arriving once a
 * second must not cause the UI to oscillate between CAR and PHONE.
 *
 * Keep the last car position for 8 seconds without a new fix; then use an
 * independently fresh phone fix. After switching to PHONE, hold that choice
 * at least 8 seconds before returning to a freshly reporting car.
 *
 * This governs launcher widgets only, never Android Auto sensor forwarding.
 */
internal class GpsSourceLatch(
    private val carLossHoldMs: Long = 8_000L,
    private val phoneMinimumMs: Long = 8_000L
) {
    enum class Source { CAR, PHONE }

    var source: Source = Source.PHONE
        private set

    private var switchedToPhoneAtMs: Long? = null

    fun select(nowMs: Long, carFixAtMs: Long?, phoneFixAtMs: Long?): Source {
        fun age(stamp: Long?): Long? =
            stamp?.takeIf { it > 0L && nowMs >= it }?.let { nowMs - it }
        val carAge = age(carFixAtMs)
        val phoneAge = age(phoneFixAtMs)
        val carFresh = carAge != null && carAge <= 3_000L
        val carHeld = carAge != null && carAge <= carLossHoldMs
        val phoneFresh = phoneAge != null && phoneAge <= 10_000L

        if (source == Source.CAR && !carHeld) {
            source = Source.PHONE
            switchedToPhoneAtMs = nowMs
        } else if (source == Source.PHONE && carFresh &&
            (!phoneFresh || switchedToPhoneAtMs == null ||
             nowMs - switchedToPhoneAtMs!! >= phoneMinimumMs)) {
            source = Source.CAR
            switchedToPhoneAtMs = null
        }
        return source
    }
}
