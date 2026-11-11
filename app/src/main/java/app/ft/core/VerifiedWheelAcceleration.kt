package app.ft.core

/**
 * One quality-gated longitudinal acceleration estimate from already VERIFIED
 * signed wheel-speed samples. No extra OEM accelerometer subscription required.
 * Units and sample epoch MUST have been validated by the upstream car adapter.
 *
 * Missing, out-of-order or long-gap samples invalidate an unfinished segment.
 * Independent acceleration is a quality metric for calibration, not a source
 * of vehicle displacement or a substitute for verified wheel speed.
 */
internal class VerifiedWheelAcceleration {
    private var previousAtMs = Long.MIN_VALUE
    private var previousMps = 0.0

    fun observe(
        monotonicMs: Long,
        forwardWheelSpeedMps: Double,
        verified: Boolean
    ): Double? {
        if (!verified || monotonicMs <= 0L ||
            !forwardWheelSpeedMps.isFinite() ||
            forwardWheelSpeedMps !in 0.0..65.0) {
            reset()
            return null
        }
        val before = previousAtMs
        val previousSpeed = previousMps
        if (before != Long.MIN_VALUE && monotonicMs <= before) {
            reset()
            return null
        }
        previousAtMs = monotonicMs
        previousMps = forwardWheelSpeedMps
        if (before == Long.MIN_VALUE) return null
        val deltaMs = monotonicMs - before
        if (deltaMs !in 20L..500L) return null
        val acceleration = (forwardWheelSpeedMps - previousSpeed) *
            1000.0 / deltaMs
        return acceleration.takeIf { it.isFinite() }
    }

    fun reset() {
        previousAtMs = Long.MIN_VALUE
        previousMps = 0.0
    }
}
