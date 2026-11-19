package app.ft.carplay

/**
 * FT200.6 source-only, latest-only buffer between verified NMEA serialization
 * and a FUTURE, separately authenticated Toyota iAP2 PASCD transport.
 *
 * Never writes to Bluetooth, Android LocationManager, CAN or VHAL. Only one
 * pending fix is retained. When a producer is faster than the real transport,
 * the oldest unsent fix is replaced, not replayed later as stale navigation.
 *
 * A candidate returned by poll() is NOT evidence that the HU requested,
 * received or used NMEA. Caller must separately enforce its real wire gate.
 */
internal class CarPlayNmeaLatestOnlyBuffer(
    private val maxPendingAgeMs: Long = 2_000L
) {
    init { require(maxPendingAgeMs in 1L..30_000L) }
    enum class State {
        ACTIVATED, ACTIVATION_DENIED, STALE_GENERATION,
        QUEUED, REPLACED_OLDER_PENDING, DUPLICATE_OR_OLDER_FIX,
        EMISSION_NOT_APPROVED, FIX_EPOCH_MISMATCH,
        INVALID_MONOTONIC_TIME, NO_AUTHORIZED_SESSION
    }

    /** Redact entire NMEA payload, including latitude, longitude and UTC. */
    class Pending internal constructor(
        val sessionGeneration: Long,
        val utcEpochMillis: Long,
        val sentences: CarPlayNmeaSentences.PairOfSentences,
        internal val enqueuedAtElapsedMs: Long
    ) {
        override fun toString() = "CarPlayPendingNmea(payload redacted)"
    }

    private var largestGeneration = 0L
    private var currentGeneration: Long? = null
    private var largestEpochMillis = Long.MIN_VALUE
    private var pending: Pending? = null

    @Synchronized
    fun begin(
        generation: Long,
        activation: CarPlayNmeaSubscriptionSession.Activation
    ): State {
        if (generation <= largestGeneration || generation <= 0L)
            return State.STALE_GENERATION
        largestGeneration = generation
        currentGeneration = null
        largestEpochMillis = Long.MIN_VALUE
        pending = null
        if (activation !=
            CarPlayNmeaSubscriptionSession.Activation.ACTIVATED_FOR_ENCODING_ONLY)
            return State.ACTIVATION_DENIED
        currentGeneration = generation
        return State.ACTIVATED
    }

    @Synchronized
    fun offer(
        generation: Long,
        epochMillis: Long,
        emission: CarPlayNmeaSubscriptionSession.Emission,
        enqueuedAtElapsedMs: Long
    ): State {
        if (currentGeneration != generation || generation <= 0L)
            return State.NO_AUTHORIZED_SESSION
        if (emission.outcome != CarPlayNmeaSubscriptionSession.Outcome.ENCODED_NOT_SENT ||
            emission.sentences == null || epochMillis !in 0L..4102444800000L)
            return State.EMISSION_NOT_APPROVED
        if (emission.fixUtcEpochMillis != epochMillis)
            return State.FIX_EPOCH_MISMATCH
        if (enqueuedAtElapsedMs < 0L)
            return State.INVALID_MONOTONIC_TIME
        if (epochMillis <= largestEpochMillis)
            return State.DUPLICATE_OR_OLDER_FIX
        largestEpochMillis = epochMillis
        val replaced = pending != null
        pending = Pending(generation, epochMillis, emission.sentences, enqueuedAtElapsedMs)
        return if (replaced) State.REPLACED_OLDER_PENDING else State.QUEUED
    }

    /** Consume once. Failed wire sends are dropped, never queued for replay. */
    @Synchronized
    fun poll(generation: Long, nowElapsedMs: Long): Pending? {
        if (currentGeneration != generation || generation <= 0L) return null
        val value = pending
        pending = null
        if (value == null || nowElapsedMs < value.enqueuedAtElapsedMs ||
            nowElapsedMs - value.enqueuedAtElapsedMs > maxPendingAgeMs)
            return null
        return value
    }

    @Synchronized
    fun onSessionLost(generation: Long) {
        if (generation == currentGeneration) {
            currentGeneration = null
            pending = null
            largestEpochMillis = Long.MIN_VALUE
        }
    }
}
