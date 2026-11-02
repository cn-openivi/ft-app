package app.ft.iccoa

/**
 * Allow one automatic rescan/reconnect only AFTER a fully established ICCOA
 * session unexpectedly drops. Do not retry failed bootstrap, PIN, GATT or
 * authentication automatically; those require a current owner decision.
 *
 * An explicit user connect/scan grants one fresh recovery opportunity.
 * The service still performs its ordinary, authenticated ICCOA handshake.
 */
internal class IccoaDropRecovery {
    private var used = false

    fun onUnexpectedDrop(wasConnected: Boolean, autoConnectEnabled: Boolean): Boolean {
        if (!wasConnected || !autoConnectEnabled || used) return false
        used = true
        return true
    }

    fun onExplicitOwnerRequest() {
        used = false
    }
}
