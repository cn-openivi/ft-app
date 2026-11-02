package app.ft.iccoa

/**
 * Coalesce OEM NotifyNavigationInfo (ControlIndex method 11) by payload.
 *
 * OEM Carlink.apk switches CarLinkSensorMgr on/off when navigation state changes.
 * No periodic refresh interval or OEM timeout has been established: send on
 * authenticated CONTROL readiness and real state changes, not on a timer.
 *
 * [accepted] is called only after the transport successfully queues a message.
 * If queueing fails, the same payload stays eligible for bounded retry.
 */
internal class IccoaControl11Gate {
    private var sentPayload: ByteArray? = null

    fun shouldSend(payload: ByteArray): Boolean =
        sentPayload?.contentEquals(payload) != true

    fun accepted(payload: ByteArray) {
        sentPayload = payload.copyOf()
    }

    /** A new authenticated CONTROL channel must receive the current state. */
    fun reset() {
        sentPayload = null
    }
}
