package app.ft.carplay

/**
 * A real, owner-initiated Bluetooth/iAP2 test reports observed protocol phases.
 * Source evidence NEVER implies trusted Apple MFi, live AirPlay video or
 * working native carrier voice. No protocol bytes or vehicle state are changed.
 */
internal object CarPlayTrialProgress {
    enum class Phase {
        NOT_STARTED, WAITING_FOR_TOYOTA, TOYOTA_RFCOMM_CONNECTED,
        IAP2_LINK_NEGOTIATED, IDENTIFICATION_REQUESTED,
        IDENTIFICATION_OBSERVED, WIFI_DIALECT_OBSERVED,
        MFI_TRUST_REQUIRED, FINISHED_WITHOUT_CARPLAY
    }
    enum class Blocker {
        NONE, BLUETOOTH_PERMISSION_OR_ADAPTER, NO_EXISTING_TOYOTA_BOND,
        NO_TOYOTA_RFCOMM, IAP2_LINK_FAILED, MFI_CERTIFICATE_UNTRUSTED,
        MFI_SIGNATURE_UNVERIFIED, IO_OR_TIMEOUT, STOPPED_BY_OWNER
    }
    data class State(
        val phase: Phase = Phase.NOT_STARTED,
        val blocker: Blocker = Blocker.NONE,
        val terminal: Boolean = false,
        val realLinkObserved: Boolean = false,
        val realControlObserved: Boolean = false
    ) {
        val carPlayAuthenticated: Boolean get() = false
        val carPlayActive: Boolean get() = false
    }

    fun start() = State(Phase.WAITING_FOR_TOYOTA)

    fun observe(current: State, event: CarPlayIncomingIapProbe.Observation): State {
        if (current.terminal || current.phase == Phase.NOT_STARTED) return current
        val failure = when (event) {
            CarPlayIncomingIapProbe.Observation.PERMISSION_MISSING,
            CarPlayIncomingIapProbe.Observation.ADAPTER_MISSING,
            CarPlayIncomingIapProbe.Observation.ADAPTER_OFF ->
                Blocker.BLUETOOTH_PERMISSION_OR_ADAPTER
            CarPlayIncomingIapProbe.Observation.NOT_BONDED,
            CarPlayIncomingIapProbe.Observation.INVALID_ADDRESS,
            CarPlayIncomingIapProbe.Observation.PEER_NOT_SELECTED_TOYOTA ->
                Blocker.NO_EXISTING_TOYOTA_BOND
            CarPlayIncomingIapProbe.Observation.ACCEPT_TIMEOUT ->
                Blocker.NO_TOYOTA_RFCOMM
            CarPlayIncomingIapProbe.Observation.LINK_REJECTED,
            CarPlayIncomingIapProbe.Observation.LINK_ACK_NOT_OBSERVED,
            CarPlayIncomingIapProbe.Observation.NO_IAP2_LINK_FRAME,
            CarPlayIncomingIapProbe.Observation.LINK_DATA_RETRY_EXHAUSTED ->
                Blocker.IAP2_LINK_FAILED
            CarPlayIncomingIapProbe.Observation.PROBE_TIMEOUT,
            CarPlayIncomingIapProbe.Observation.IO_FAILURE ->
                Blocker.IO_OR_TIMEOUT
            CarPlayIncomingIapProbe.Observation.CANCELLED ->
                Blocker.STOPPED_BY_OWNER
            else -> null
        }
        if (failure != null) {
            // A 30-second deadline must not erase a previously observed
            // RFCOMM/LINK/CONTROL phase or an MFi trust blocker. Keep the
            // furthest authentic evidence when the physical probe ends.
            val retainedBlocker = if (current.phase == Phase.MFI_TRUST_REQUIRED &&
                failure == Blocker.IO_OR_TIMEOUT) current.blocker else failure
            return current.copy(
                phase = if (current.phase == Phase.WAITING_FOR_TOYOTA)
                    Phase.FINISHED_WITHOUT_CARPLAY else current.phase,
                blocker = retainedBlocker,
                terminal = true
            )
        }
        val advanced = when (event) {
            CarPlayIncomingIapProbe.Observation.ACTUAL_TOYOTA_RFCOMM_CONNECTED ->
                Phase.TOYOTA_RFCOMM_CONNECTED
            CarPlayIncomingIapProbe.Observation.LINK_NEGOTIATED ->
                Phase.IAP2_LINK_NEGOTIATED
            CarPlayIncomingIapProbe.Observation.IDENTIFICATION_REQUEST_SENT ->
                Phase.IDENTIFICATION_REQUESTED
            CarPlayIncomingIapProbe.Observation.IDENTIFICATION_INFO_OBSERVED,
            CarPlayIncomingIapProbe.Observation.IDENTIFICATION_INFO_UNKNOWN_PARAMETERS ->
                Phase.IDENTIFICATION_OBSERVED
            CarPlayIncomingIapProbe.Observation.WIFI_DIALECT_GROUPED_HINT,
            CarPlayIncomingIapProbe.Observation.WIFI_DIALECT_FLAT_HINT ->
                Phase.WIFI_DIALECT_OBSERVED
            CarPlayIncomingIapProbe.Observation.ACCESSORY_AUTH_CERTIFICATE_RECEIVED_UNTRUSTED,
            CarPlayIncomingIapProbe.Observation.ACCESSORY_AUTH_RESPONSE_RECEIVED_UNVERIFIED ->
                Phase.MFI_TRUST_REQUIRED
            else -> current.phase
        }
        val blocker = when (event) {
            CarPlayIncomingIapProbe.Observation.ACCESSORY_AUTH_CERTIFICATE_RECEIVED_UNTRUSTED ->
                Blocker.MFI_CERTIFICATE_UNTRUSTED
            CarPlayIncomingIapProbe.Observation.ACCESSORY_AUTH_RESPONSE_RECEIVED_UNVERIFIED ->
                Blocker.MFI_SIGNATURE_UNVERIFIED
            else -> current.blocker
        }
        return current.copy(
            phase = if (advanced.ordinal > current.phase.ordinal) advanced else current.phase,
            blocker = blocker,
            realLinkObserved = current.realLinkObserved || event ==
                CarPlayIncomingIapProbe.Observation.LINK_NEGOTIATED,
            realControlObserved = current.realControlObserved || event in setOf(
                CarPlayIncomingIapProbe.Observation.CONTROL_MESSAGE_OBSERVED,
                CarPlayIncomingIapProbe.Observation.IDENTIFICATION_INFO_OBSERVED,
                CarPlayIncomingIapProbe.Observation.CARPLAY_START_SESSION_OBSERVED,
                CarPlayIncomingIapProbe.Observation.FLAT_WIFI_INFO_STRUCTURE_OBSERVED
            )
        )
    }
}
