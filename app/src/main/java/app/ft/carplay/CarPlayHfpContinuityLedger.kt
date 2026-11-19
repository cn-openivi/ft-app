package app.ft.carplay

/**
 * FT200.3: bounded read-only evidence of native Android HFP continuity for
 * one already-selected Toyota Bluetooth bond. An HFP profile snapshot is NOT
 * cellular speech, OEM CarPlay authentication or an ACTIVE AirPlay session.
 *
 * Probe generations are local diagnostic lifetimes, deliberately NOT CarPlay
 * session generations. No Bluetooth addresses, call details or audio stored.
 */
internal class CarPlayHfpContinuityLedger(
    private val freshnessMs: Long = 30_000L
) {
    init { require(freshnessMs in 1L..300_000L) }

    enum class State {
        UNOBSERVED, SAME_HU_PROFILE_ONLY, HFP_DISCONNECTED,
        DIFFERENT_HU, NO_BOND, PROFILE_UNAVAILABLE, EXPIRED,
        STALE_PROBE_IGNORED
    }

    private var currentProbe = 0L
    private var lastObservedAtMs = -1L
    private var latest = State.UNOBSERVED

    @Synchronized
    fun beginProbe(generation: Long): Boolean {
        if (generation <= currentProbe || generation <= 0L) return false
        currentProbe = generation
        lastObservedAtMs = -1L
        latest = State.UNOBSERVED
        return true
    }

    @Synchronized
    fun observe(
        generation: Long,
        result: CarPlaySingleBondPolicy.Result,
        monotonicMs: Long
    ): State {
        if (generation != currentProbe || generation <= 0L ||
            monotonicMs < 0L ||
            (lastObservedAtMs >= 0L && monotonicMs < lastObservedAtMs))
            return State.STALE_PROBE_IGNORED
        latest = when (result) {
            CarPlaySingleBondPolicy.Result.HFP_CONNECTED_SAME_HU ->
                State.SAME_HU_PROFILE_ONLY
            CarPlaySingleBondPolicy.Result.HFP_CONNECTED_OTHER_HU ->
                State.DIFFERENT_HU
            CarPlaySingleBondPolicy.Result.HFP_DISCONNECTED ->
                State.HFP_DISCONNECTED
            CarPlaySingleBondPolicy.Result.NO_EXISTING_BOND ->
                State.NO_BOND
            else -> State.PROFILE_UNAVAILABLE
        }
        lastObservedAtMs = monotonicMs
        return latest
    }

    @Synchronized
    fun state(nowMs: Long): State {
        if (lastObservedAtMs < 0L) return State.UNOBSERVED
        if (nowMs < lastObservedAtMs || nowMs - lastObservedAtMs > freshnessMs)
            return State.EXPIRED
        return latest
    }
}
