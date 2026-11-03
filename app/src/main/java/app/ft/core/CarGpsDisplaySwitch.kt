package app.ft.core

/**
 * Display-only GPS source hysteresis. Does not filter, correct or forward GPS
 * coordinates: decoded ICCOA location always wins while the car is supplying it.
 *
 * After 8 s without a new car position, show the phone. Once the car resumes,
 * wait for 8 s of continued positions before selecting the car again.
 * The first-ever car position can be shown immediately.
 */
internal class CarGpsDisplaySwitch(
    private val carSilenceMs: Long = 8_000L,
    private val carRecoveryMs: Long = 8_000L,
    private val continuousFrameGapMs: Long = 2_500L
) {
    enum class Source { CAR, PHONE }

    var source: Source = Source.PHONE
        private set

    private var lastCarPositionAtMs = 0L
    private var recoveryStartedAtMs = 0L

    fun select(nowMs: Long, carPositionAtMs: Long?): Source {
        val positionAt = carPositionAtMs?.takeIf { it > 0L && it <= nowMs }
        if (positionAt != null && positionAt > lastCarPositionAtMs) {
            if (lastCarPositionAtMs == 0L) {
                source = Source.CAR
            } else if (source == Source.PHONE) {
                if (recoveryStartedAtMs == 0L ||
                    positionAt - lastCarPositionAtMs > continuousFrameGapMs) {
                    recoveryStartedAtMs = positionAt
                } else if (positionAt - recoveryStartedAtMs >= carRecoveryMs) {
                    source = Source.CAR
                    recoveryStartedAtMs = 0L
                }
            }
            lastCarPositionAtMs = positionAt
        }

        if (source == Source.CAR &&
            (lastCarPositionAtMs == 0L || nowMs - lastCarPositionAtMs > carSilenceMs)) {
            source = Source.PHONE
            recoveryStartedAtMs = 0L
        }

        if (source == Source.PHONE && lastCarPositionAtMs > 0L &&
            nowMs - lastCarPositionAtMs > continuousFrameGapMs) {
            recoveryStartedAtMs = 0L
        }
        return source
    }
}
