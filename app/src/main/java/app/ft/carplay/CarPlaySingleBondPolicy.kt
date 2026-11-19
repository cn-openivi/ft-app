package app.ft.carplay

import java.util.Locale

/**
 * FT200.3 pure verifier for ONE already-bonded real Toyota device.
 * The remote Toyota Bluetooth address is NOT the phone's hidden local MAC.
 * Android API restrictions may make local adapter address unobservable.
 *
 * A matching HFP peer proves only profile identity, NOT microphone/SCO,
 * downstream carrier audio or OEM mDeviceAddr == mHFPAddr.
 */
internal object CarPlaySingleBondPolicy {
    enum class Result {
        MISSING_SELECTED_HU, INVALID_HU_ADDRESS, NO_EXISTING_BOND,
        HFP_NOT_OBSERVED, HFP_CONNECTED_SAME_HU, HFP_CONNECTED_OTHER_HU,
        HFP_DISCONNECTED, BT_STATE_UNAVAILABLE
    }

    data class Snapshot(
        val selectedHuAddress: String?,
        val bondedHuAddresses: Set<String>?,
        val hfpPeerAddress: String?,
        val hfpConnected: Boolean?
    )

    fun evaluate(value: Snapshot): Result {
        val selected = canonical(value.selectedHuAddress)
            ?: return if (value.selectedHuAddress == null)
                Result.MISSING_SELECTED_HU else Result.INVALID_HU_ADDRESS
        val bonds = value.bondedHuAddresses ?: return Result.BT_STATE_UNAVAILABLE
        if (bonds.mapNotNull(::canonical).none { it == selected })
            return Result.NO_EXISTING_BOND
        val hfp = value.hfpPeerAddress?.let(::canonical)
        if (value.hfpPeerAddress != null && hfp == null)
            return Result.BT_STATE_UNAVAILABLE
        if (value.hfpConnected == null) return Result.BT_STATE_UNAVAILABLE
        if (!value.hfpConnected) return Result.HFP_DISCONNECTED
        if (hfp == null) return Result.HFP_NOT_OBSERVED
        return if (hfp == selected) Result.HFP_CONNECTED_SAME_HU
            else Result.HFP_CONNECTED_OTHER_HU
    }

    private fun canonical(addr: String?): String? {
        if (addr == null || !Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(addr))
            return null
        val result = addr.uppercase(Locale.ROOT)
        if (result == "00:00:00:00:00:00" || result == "02:00:00:00:00:00" ||
            (result.substring(0, 2).toInt(16) and 1) != 0) return null
        return result
    }
}

/**
 * In/out cellular call evidence is a SEPARATE final gate from CarPlay ACTIVE.
 * It is impossible to infer actual remote speech from ringing or HFP pairing.
 */
internal class CarPlayCarrierCallEvidence {
    enum class Direction { INCOMING, OUTGOING }
    enum class Status {
        NOT_VERIFIED, SESSION_EVIDENCE_MISSING, STALE_OR_REVOKED_SESSION,
        WRONG_HFP_PEER, NO_CARPLAY_SESSION,
        MISSING_REAL_OPERATOR_CALL, MISSING_TWOWAY_SPEECH,
        INCOMING_VERIFIED_ONLY, OUTGOING_VERIFIED_ONLY, BOTH_DIRECTIONS_VERIFIED
    }

    /**
     * sessionGeneration must come from the same genuinely observed CarPlay HU
     * session lifecycle, not a synthesized per-call counter. Boolean speech
     * evidence must be independently observed by the final field tester;
     * this policy does not sniff or intercept protected carrier audio.
     */
    data class Observation(
        val direction: Direction,
        val actualCarrierCall: Boolean,
        val carPlayHuActive: Boolean,
        val hfpPeer: CarPlaySingleBondPolicy.Result,
        val micToFarEndHeard: Boolean,
        val farEndToToyotaHeard: Boolean,
        val sessionGeneration: Long = 0L
    )

    private val approved = mutableSetOf<Direction>()
    private var activeGeneration = 0L
    private var revokedThroughGeneration = 0L
    private var lastFailure = Status.NOT_VERIFIED

    @Synchronized
    fun record(observation: Observation): Status {
        val id = observation.sessionGeneration
        if (id <= 0L) return Status.SESSION_EVIDENCE_MISSING
        if (id <= revokedThroughGeneration || id < activeGeneration)
            return Status.STALE_OR_REVOKED_SESSION
        if (id > activeGeneration) {
            activeGeneration = id
            approved.clear() // Never combine incoming and outgoing across reconnect.
            lastFailure = Status.NOT_VERIFIED
        }

        val failure = when {
            observation.hfpPeer != CarPlaySingleBondPolicy.Result.HFP_CONNECTED_SAME_HU ->
                Status.WRONG_HFP_PEER
            !observation.carPlayHuActive -> Status.NO_CARPLAY_SESSION
            !observation.actualCarrierCall -> Status.MISSING_REAL_OPERATOR_CALL
            !observation.micToFarEndHeard || !observation.farEndToToyotaHeard ->
                Status.MISSING_TWOWAY_SPEECH
            else -> null
        }
        if (failure != null) {
            // A failed later call must withdraw a previous positive BOTH proof.
            approved.clear()
            lastFailure = failure
            return failure
        }
        approved.add(observation.direction)
        lastFailure = Status.NOT_VERIFIED
        return status()
    }

    @Synchronized
    fun status(): Status {
        if (approved.isEmpty()) return lastFailure
        return when (approved) {
            setOf(Direction.INCOMING) -> Status.INCOMING_VERIFIED_ONLY
            setOf(Direction.OUTGOING) -> Status.OUTGOING_VERIFIED_ONLY
            else -> Status.BOTH_DIRECTIONS_VERIFIED
        }
    }

    @Synchronized
    fun onSessionLost(generation: Long) {
        if (generation != activeGeneration || generation <= 0L) return
        revokedThroughGeneration = maxOf(revokedThroughGeneration, generation)
        approved.clear()
        lastFailure = Status.NOT_VERIFIED
    }

    @Synchronized
    fun reset() {
        revokedThroughGeneration = maxOf(revokedThroughGeneration, activeGeneration)
        activeGeneration = 0L
        approved.clear()
        lastFailure = Status.NOT_VERIFIED
    }
}
