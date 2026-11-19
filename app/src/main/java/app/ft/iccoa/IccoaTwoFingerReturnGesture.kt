package app.ft.iccoa

import app.ft.projection.CarTouch

/**
 * Two stationary contacts held for 2.5 seconds return from direct H.264 to FT.
 * A single finger never enters this recognizer. A failed two-finger attempt
 * cannot produce a synthetic UP/tap on Android Auto.
 *
 * The caller owns the main-thread timer; [fire] validates continuous contact.
 */
internal class IccoaTwoFingerReturnGesture(
    val holdMs: Long = 2_500L,
    private val slopPx: Int = 32
) {
    enum class Decision { PASS, START_HOLD, HOLDING, RESTART_PRIMARY, CONSUME }

    private enum class Phase { IDLE, ARMED, SUPPRESS, COMPLETED, PASS_THROUGH }
    private var phase = Phase.IDLE
    private var startMs = 0L
    private var firstX = 0
    private var firstY = 0
    private var secondX = 0
    private var secondY = 0

    val armed: Boolean get() = phase == Phase.ARMED

    fun reset() { phase = Phase.IDLE }

    fun observe(touch: CarTouch, nowMs: Long): Decision {
        if (touch.action == CarTouch.DOWN) reset()
        // ICCOA receivers vary: a two-contact sequence can begin with DOWN,
        // POINTER_DOWN, or a MOVE carrying both coordinates. All three are
        // bounded by the same stationary hold, never a one-finger shortcut.
        if (phase == Phase.IDLE &&
            touch.action in setOf(CarTouch.DOWN, CarTouch.POINTER_DOWN, CarTouch.MOVE) &&
            touch.x2 >= 0 && touch.y2 >= 0) {
            phase = Phase.ARMED
            startMs = nowMs
            firstX = touch.x
            firstY = touch.y
            secondX = touch.x2
            secondY = touch.y2
            return Decision.START_HOLD
        }
        if (touch.action == CarTouch.DOWN) return Decision.PASS
        if (phase == Phase.PASS_THROUGH) {
            // After a pinch/drag began, do not re-arm the 2.5 s hold from
            // another multi-contact MOVE within this same touch sequence.
            if (touch.action == CarTouch.UP || touch.action == CarTouch.CANCEL) reset()
            return Decision.PASS
        }

        if (phase == Phase.IDLE) return Decision.PASS
        if (touch.action == CarTouch.UP || touch.action == CarTouch.CANCEL) {
            reset()
            return Decision.CONSUME
        }
        if (touch.action == CarTouch.POINTER_UP) {
            phase = Phase.SUPPRESS
            return Decision.CONSUME
        }
        if (phase != Phase.ARMED) return Decision.CONSUME
        if (touch.action == CarTouch.MOVE && (
                touch.x2 < 0 || touch.y2 < 0 ||
                kotlin.math.abs(touch.x - firstX) > slopPx ||
                kotlin.math.abs(touch.y - firstY) > slopPx ||
                kotlin.math.abs(touch.x2 - secondX) > slopPx ||
                kotlin.math.abs(touch.y2 - secondY) > slopPx)) {
            // Restore an ordinary one-finger AA gesture after a moving
            // two-finger attempt; never turn it into a held FT return.
            phase = Phase.PASS_THROUGH
            return Decision.RESTART_PRIMARY
        }
        return Decision.HOLDING
    }

    fun fire(nowMs: Long): Boolean {
        if (phase != Phase.ARMED || nowMs - startMs < holdMs) return false
        phase = Phase.COMPLETED
        return true
    }
}
