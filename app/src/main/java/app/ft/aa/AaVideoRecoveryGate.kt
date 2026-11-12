package app.ft.aa

/**
 * Pure gate for video input starvation. A lost AVC access unit may be a P-frame
 * reference; request a fresh picture at most once per 2.5 seconds per decoder
 * generation, without adding new protocol messages or blocking AA ACK dispatch.
 */
internal class AaVideoRecoveryGate(private val cooldownNs: Long = 2_500_000_000L) {
    private var lastRequestNs: Long? = null

    fun onLoss(nowNs: Long): Boolean {
        val last = lastRequestNs
        if (last != null && nowNs >= last && nowNs - last < cooldownNs) return false
        lastRequestNs = nowNs
        return true
    }

    fun reset() { lastRequestNs = null }
}
