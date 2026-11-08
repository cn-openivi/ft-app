package app.ft.iccoa

/**
 * Infinite *waiting* with bounded, low-power BLE windows. This does not
 * authorize unbounded PIN/Quick credential replay or continuous radio polling.
 * Only the OS service lifecycle and the owner toggle decide when waiting ends.
 */
internal class IccoaSeekPolicy {
    private var missingWindows = 0L

    fun nextDelayMs(): Long {
        if (missingWindows < Long.MAX_VALUE) missingWindows++
        return when {
            missingWindows == 1L -> 5_000L
            missingWindows == 2L -> 10_000L
            else -> 25_000L
        }
    }

    fun reset() {
        missingWindows = 0L
    }
}
