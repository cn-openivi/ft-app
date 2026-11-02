package app.ft.iccoa

/**
 * Coalesce OEM NotifyNavigationInfo (ControlIndex method 11) frames.
 *
 * By default the OEM state is synchronized only on CONTROL authentication,
 * actual payload changes and failed-send retry. Certain head units might
 * forget this state; an explicitly opted-in compatibility mode allows an
 * unchanged ACTIVE request to be reasserted at most once per [REFRESH_MS].
 *
 * This is not proof that any particular head unit needs a heartbeat.
 */
internal class IccoaControl11Gate {
    companion object { const val REFRESH_MS = 15_000L }

    private var sentPayload: ByteArray? = null
    private var sentAtMs = -1L

    fun shouldSend(payload: ByteArray, nowMs: Long, periodicActiveRefresh: Boolean = false): Boolean {
        val previous = sentPayload ?: return true
        if (!previous.contentEquals(payload)) return true
        if (!periodicActiveRefresh) return false
        // Compare a monotonic clock; never resend more often than REFRESH_MS.
        return nowMs < sentAtMs || nowMs - sentAtMs >= REFRESH_MS
    }

    /** Called only when sendControl successfully enqueues the frame. */
    fun accepted(payload: ByteArray, nowMs: Long) {
        sentPayload = payload.copyOf()
        sentAtMs = nowMs
    }

    /** A new authenticated CONTROL lane must receive the current state. */
    fun reset() {
        sentPayload = null
        sentAtMs = -1L
    }
}
