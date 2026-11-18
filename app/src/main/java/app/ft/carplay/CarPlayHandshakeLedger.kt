package app.ft.carplay

/**
 * FT200.2 source-only state ledger. Each phase records an observed real-world event,
 * not a successful CarPlay transport by virtue of having passed unit tests.
 *
 * This ledger does NOT enable CarProtocol.CARPLAY. The shipping feature flag remains OFF.
 * No Bluetooth identity, iAP2 service UUID, MFi credential or synthetic HU event is created.
 */
internal class CarPlayHandshakeLedger(
    private val phaseTimeoutMs: Long = 15_000L
) {
    enum class Phase {
        UNAVAILABLE, BT_DISCOVERED, IAP2_NEGOTIATING,
        WIFI_NEGOTIATING, AIRPLAY_NEGOTIATING, ACTIVE, BLOCKED
    }

    enum class Reason {
        NONE, NO_EXISTING_BOND, SDP_NOT_VERIFIED, IAP2_AUTH_FAILED,
        WIFI_HANDOVER_FAILED, AIRPLAY_SETUP_FAILED,
        HU_VIDEO_OR_TOUCH_NOT_CONFIRMED, PHASE_TIMEOUT, OUT_OF_ORDER
    }

    data class Status(
        val phase: Phase,
        val reason: Reason,
        val updatedAtMs: Long,
        val observedHuActive: Boolean = false,
        val observedFrames: Boolean = false,
        val observedTouch: Boolean = false
    )

    var status = Status(Phase.UNAVAILABLE, Reason.NONE, 0L)
        private set

    /** Bond + completed SDP observation are only BT discovery, never iAP2 proof. */
    fun observeBluetooth(existingBond: Boolean, sdpObserved: Boolean, nowMs: Long): Boolean {
        if (status.phase != Phase.UNAVAILABLE) return reject(Reason.OUT_OF_ORDER, nowMs)
        if (!existingBond) return reject(Reason.NO_EXISTING_BOND, nowMs)
        if (!sdpObserved) return reject(Reason.SDP_NOT_VERIFIED, nowMs)
        status = Status(Phase.BT_DISCOVERED, Reason.NONE, nowMs)
        return true
    }

    /** A verified iAP2 service, not an arbitrary RFCOMM UUID, is required. */
    fun startIap2(verifiedProfile: Boolean, nowMs: Long): Boolean =
        advance(Phase.BT_DISCOVERED, Phase.IAP2_NEGOTIATING, nowMs,
            verifiedProfile, Reason.SDP_NOT_VERIFIED)

    /** Actual iAP2 negotiation + required authentication must have succeeded. */
    fun iap2Established(realSessionAndAuth: Boolean, nowMs: Long): Boolean =
        advance(Phase.IAP2_NEGOTIATING, Phase.WIFI_NEGOTIATING, nowMs,
            realSessionAndAuth, Reason.IAP2_AUTH_FAILED)

    /** Real Wi-Fi handover, not simply Wi-Fi turned on. */
    fun wifiEstablished(realHandover: Boolean, nowMs: Long): Boolean =
        advance(Phase.WIFI_NEGOTIATING, Phase.AIRPLAY_NEGOTIATING, nowMs,
            realHandover, Reason.WIFI_HANDOVER_FAILED)

    /**
     * ACTIVE requires evidence from the physical Toyota: accepted AirPlay,
     * actual displayed frames and working reverse touch.
     * HFP cellular bidirectional speech is a separate mandatory FT200.4 gate.
     */
    fun confirmAirPlay(
        setupSucceeded: Boolean,
        huReportsActive: Boolean,
        visibleFrames: Boolean,
        reverseTouch: Boolean,
        nowMs: Long
    ): Boolean {
        if (status.phase != Phase.AIRPLAY_NEGOTIATING) return reject(Reason.OUT_OF_ORDER, nowMs)
        if (timedOut(nowMs)) return reject(Reason.PHASE_TIMEOUT, nowMs)
        if (!setupSucceeded) return reject(Reason.AIRPLAY_SETUP_FAILED, nowMs)
        if (!huReportsActive || !visibleFrames || !reverseTouch)
            return reject(Reason.HU_VIDEO_OR_TOUCH_NOT_CONFIRMED, nowMs)
        status = Status(Phase.ACTIVE, Reason.NONE, nowMs, true, true, true)
        return true
    }

    fun checkTimeout(nowMs: Long): Boolean {
        if (status.phase == Phase.UNAVAILABLE || status.phase == Phase.ACTIVE ||
            status.phase == Phase.BLOCKED) return false
        if (!timedOut(nowMs)) return false
        return reject(Reason.PHASE_TIMEOUT, nowMs).let { true }
    }

    /** Reconnect starts a new evidence chain; never retains stale ACTIVE. */
    fun reset(nowMs: Long = 0L) {
        status = Status(Phase.UNAVAILABLE, Reason.NONE, nowMs)
    }

    private fun advance(
        expected: Phase, next: Phase, nowMs: Long,
        proven: Boolean, onFailure: Reason
    ): Boolean {
        if (status.phase != expected) return reject(Reason.OUT_OF_ORDER, nowMs)
        if (timedOut(nowMs)) return reject(Reason.PHASE_TIMEOUT, nowMs)
        if (!proven) return reject(onFailure, nowMs)
        status = Status(next, Reason.NONE, nowMs)
        return true
    }

    private fun timedOut(nowMs: Long): Boolean =
        nowMs < status.updatedAtMs || nowMs - status.updatedAtMs > phaseTimeoutMs

    private fun reject(reason: Reason, nowMs: Long): Boolean {
        status = Status(Phase.BLOCKED, reason, nowMs)
        return false
    }
}
