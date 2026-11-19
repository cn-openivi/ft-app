package app.ft.carplay

/**
 * FT200.6 source-only delivery eligibility, not a PASCD transport.
 * Only an independently authenticated iAP2 session with a real HU request
 * and a Toyota-confirmed NMEA profile can encode car GNSS/verified EKF DR.
 * Resetting the session drops its authority and previous-fix state. No
 * automatic phone-GPS fallback, VHAL/ECU writes, or synthetic subscriptions.
 */
internal class CarPlayNmeaSubscriptionSession {
    enum class Activation {
        ACTIVATED_FOR_ENCODING_ONLY, STALE_GENERATION,
        AUTHENTICATION_REQUIRED, HU_REQUEST_MISSING, PROFILE_NOT_CONFIRMED
    }
    enum class Outcome {
        SESSION_MISSING_OR_CHANGED, FIX_NOT_NEWER, SOURCE_REJECTED,
        ENCODE_REJECTED, ENCODED_NOT_SENT
    }
    class Emission(
        val outcome: Outcome,
        val sentences: CarPlayNmeaSentences.PairOfSentences? = null,
        /** The exact source epoch; never reconstructed by a downstream queue. */
        val fixUtcEpochMillis: Long? = null
    ) {
        override fun toString(): String = "CarPlayGpsEmission($outcome, coordinates redacted)"
    }

    private var largestGeneration = 0L
    private var activeGeneration: Long? = null
    private var lastEpochMillis = Long.MIN_VALUE

    @Synchronized
    fun begin(
        generation: Long,
        realIap2Authenticated: Boolean,
        huRequestedLocation: Boolean,
        toyotaVerifiedNmeaGgaRmcProfile: Boolean
    ): Activation {
        // Starting a new attempt invalidates the previous session even when
        // the next attempt fails. Reject replayed and out-of-order attempts.
        if (generation <= largestGeneration || generation <= 0L)
            return Activation.STALE_GENERATION
        activeGeneration = null
        lastEpochMillis = Long.MIN_VALUE
        largestGeneration = generation
        if (!realIap2Authenticated) return Activation.AUTHENTICATION_REQUIRED
        if (!huRequestedLocation) return Activation.HU_REQUEST_MISSING
        if (!toyotaVerifiedNmeaGgaRmcProfile) return Activation.PROFILE_NOT_CONFIRMED
        activeGeneration = generation
        return Activation.ACTIVATED_FOR_ENCODING_ONLY
    }

    @Synchronized
    fun encode(
        generation: Long, fix: CarPlayNmeaSentences.Fix,
        independentlyVerifiedWgs84: Boolean
    ): Emission {
        if (generation != activeGeneration || activeGeneration == null)
            return Emission(Outcome.SESSION_MISSING_OR_CHANGED)
        if (fix.utcEpochMillis <= lastEpochMillis)
            return Emission(Outcome.FIX_NOT_NEWER)
        val preflight = CarPlayMediaPreflight.gps(
            authenticated = true, huSubscribed = true,
            negotiatedFormat = "NMEA_GGA_RMC_CONFIRMED",
            fix = CarPlayMediaPreflight.Fix(
                fix.latitude, fix.longitude, fix.source,
                independentlyVerifiedWgs84, fix.verifiedFusion
            )
        )
        if (preflight != CarPlayMediaPreflight.GpsResult.READY_TO_ENCODE_NOT_SENT)
            return Emission(Outcome.SOURCE_REJECTED)
        val sentences = CarPlayNmeaSentences.encodeIfApproved(preflight, fix)
            ?: return Emission(Outcome.ENCODE_REJECTED)
        lastEpochMillis = fix.utcEpochMillis
        return Emission(Outcome.ENCODED_NOT_SENT, sentences, fix.utcEpochMillis)
    }

    @Synchronized
    fun onSessionLost(generation: Long) {
        // A delayed callback from an old session must not kill a newer one.
        if (generation == activeGeneration) {
            activeGeneration = null
            lastEpochMillis = Long.MIN_VALUE
        }
    }
}
