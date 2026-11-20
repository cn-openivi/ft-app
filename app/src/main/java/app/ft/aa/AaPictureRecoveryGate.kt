package app.ft.aa

/** Bounded AA first-picture recovery. ICCOA, Quick Auth and HFP stay connected. */
internal class AaPictureRecoveryGate {
    enum class Action { NONE, RECREATE_SURFACE, REQUEST_FRESH_PICTURE, SHOW_MANUAL_RETRY }
    private var startedAt: Long? = null
    private var attempts = 0

    fun check(nowMs: Long, streaming: Boolean, pictureReady: Boolean, surfaceReady: Boolean): Action {
        if (!streaming || pictureReady) { reset(); return Action.NONE }
        val since = startedAt
        if (since == null || nowMs < since) {
            startedAt = nowMs
            attempts = 0
            return Action.NONE
        }
        val elapsed = nowMs - since
        return when {
            attempts == 0 && elapsed >= 4000L -> {
                attempts++
                if (surfaceReady) Action.REQUEST_FRESH_PICTURE else Action.RECREATE_SURFACE
            }
            attempts == 1 && elapsed >= 9000L -> { attempts++; Action.RECREATE_SURFACE }
            attempts == 2 && elapsed >= 14000L -> { attempts++; Action.SHOW_MANUAL_RETRY }
            else -> Action.NONE
        }
    }

    fun reset() { startedAt = null; attempts = 0 }
}
