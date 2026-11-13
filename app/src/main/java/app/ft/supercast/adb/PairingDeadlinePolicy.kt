package app.ft.supercast.adb

/**
 * Discovery deadline has no authority to interrupt an already accepted
 * pairing code or the subsequent TLS/helper handshake.
 */
internal object PairingDeadlinePolicy {
    fun shouldExpire(phase: WirelessPairingService.Phase, busy: Boolean): Boolean =
        !busy && when (phase) {
            WirelessPairingService.Phase.SEARCHING,
            WirelessPairingService.Phase.CODE_READY,
            WirelessPairingService.Phase.FAILED -> true
            else -> false
        }
}
