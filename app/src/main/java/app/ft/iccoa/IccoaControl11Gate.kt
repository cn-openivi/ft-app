package app.ft.iccoa

/**
 * Coalesce duplicate OEM NotifyNavigationInfo (ControlIndex method 11) frames.
 * True navigation payload changes still propagate immediately. An unchanged
 * request is reasserted after a bounded interval for receivers that forget it.
 * This is a send decision only; no change in vehicle safety state or sensor data.
 */
internal class IccoaControl11Gate(private val refreshMs: Long = 15_000L) {
    private var sentPayload: ByteArray? = null
    private var sentAtMs: Long = -1L

    init { require(refreshMs > 0L) }

    fun shouldSend(payload: ByteArray, nowMs: Long): Boolean {
        val previous = sentPayload ?: return true
        if (!previous.contentEquals(payload)) return true
        if (nowMs < sentAtMs) return true
        return nowMs - sentAtMs >= refreshMs
    }

    /** Called only after sendControl successfully enqueues a frame. */
    fun accepted(payload: ByteArray, nowMs: Long) {
        sentPayload = payload.copyOf()
        sentAtMs = nowMs
    }

    /** Required on disconnect or a fresh authenticated CONTROL lane. */
    fun reset() {
        sentPayload = null
        sentAtMs = -1L
    }
}
