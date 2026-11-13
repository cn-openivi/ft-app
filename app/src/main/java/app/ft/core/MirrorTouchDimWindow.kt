package app.ft.core

/**
 * Pure timer policy. Brightening comes only from PHONE physical touch; HU input is excluded.
 * Returning to a dark screen never means stopping the MediaProjection image.
 */
internal class MirrorTouchDimWindow {
    companion object { const val IDLE_MS: Long = 30_000L }
    private var active = false
    private var lastTouchAt = -1L
    fun start() { active = true; lastTouchAt = -1L }
    fun stop() { active = false; lastTouchAt = -1L }
    fun touched(nowMs: Long) {
        if (active) lastTouchAt = nowMs
    }
    fun isDue(nowMs: Long): Boolean =
        active && lastTouchAt >= 0 && nowMs - lastTouchAt >= IDLE_MS
    fun remainingMs(nowMs: Long): Long? =
        if (!active || lastTouchAt < 0) null else (IDLE_MS - (nowMs - lastTouchAt)).coerceAtLeast(1L)
    fun darkened() { lastTouchAt = -1L }
}
