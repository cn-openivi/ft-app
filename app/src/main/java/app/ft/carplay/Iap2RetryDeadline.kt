package app.ft.carplay

/**
 * Deterministic monotonic-clock retry deadlines for one outstanding iAP2 LINK
 * DATA frame. No Android APIs, global timers or socket writes here.
 * A caller-owned disposable worker invokes poll() on its own clock.
 */
internal class Iap2RetryDeadline(
    private val window: Iap2ReliableLinkWindow,
    private val timeoutMs: Long = 1200
) {
    init { require(timeoutMs in 100..30000) }

    private var deadlineMs: Long? = null
    private var exhausted = false

    @Synchronized
    fun onFirstSent(nowMs: Long): Boolean {
        if (window.pendingCount() != 1) return false
        deadlineMs = nowMs + timeoutMs
        exhausted = false
        return true
    }

    @Synchronized
    fun poll(nowMs: Long): Iap2ReliableLinkWindow.Outgoing {
        if (window.pendingCount() == 0) {
            deadlineMs = null
            exhausted = false
            return Iap2ReliableLinkWindow.Outgoing(
                Iap2ReliableLinkWindow.SendStatus.NOTHING_PENDING
            )
        }
        if (exhausted) {
            return Iap2ReliableLinkWindow.Outgoing(
                Iap2ReliableLinkWindow.SendStatus.RETRY_EXHAUSTED
            )
        }
        val due = deadlineMs ?: return Iap2ReliableLinkWindow.Outgoing(
            Iap2ReliableLinkWindow.SendStatus.WAITING_TIMEOUT
        )
        if (nowMs < due) {
            return Iap2ReliableLinkWindow.Outgoing(
                Iap2ReliableLinkWindow.SendStatus.WAITING_TIMEOUT
            )
        }
        val result = window.retryOutstanding()
        when (result.status) {
            Iap2ReliableLinkWindow.SendStatus.RETRY -> deadlineMs = nowMs + timeoutMs
            Iap2ReliableLinkWindow.SendStatus.RETRY_EXHAUSTED -> {
                deadlineMs = null
                exhausted = true
            }
            else -> deadlineMs = null
        }
        return result
    }

    @Synchronized
    fun reset() {
        deadlineMs = null
        exhausted = false
    }
}
