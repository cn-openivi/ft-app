package app.ft.projection

import android.content.Context
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Target follows the real virtual-display content geometry. */
internal object IccoaWelcomeTarget {
    const val RADIUS_FRACTION = 0.13f

    fun contains(x: Int, y: Int, width: Int, height: Int): Boolean {
        if (width < 200 || height < 150 || x !in 0 until width || y !in 0 until height) return false
        val r = minOf(width, height) * RADIUS_FRACTION
        val dx = x - width / 2.0
        val dy = y - height / 2.0
        return dx * dx + dy * dy <= r * r
    }
}

/** Dynamic UIBC mapping is primary. Four-point correction only on failed OK hit. */
internal object IccoaTouchWelcome {
    enum class Phase { NONE, CHECK, FOUR_POINTS }
    data class State(val phase: Phase = Phase.NONE)
    data class TouchResult(val consumed: Boolean = false, val completed: Boolean = false)

    private const val PREF = "ft-iccoa-touch-welcome"
    private const val LAST = "last_verified_welcome"
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var swallowUntilUp = false

    val awaiting: Boolean get() = _state.value.phase != Phase.NONE

    fun begin(context: Context, width: Int, height: Int) {
        if (width < 200 || height < 150) return
        val key = IccoaCalibrationScope.key("welcome") ?: return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putString(LAST, key).apply()
        swallowUntilUp = false
        _state.value = State(if (prefs.getBoolean(key, false)) Phase.NONE else Phase.CHECK)
        DiagLog.i("ICCOA", "Touch welcome required=${awaiting}; receiver identity verified")
    }

    /** Called before any FT widget/AA/mirror touch routing; raw mapped content coords. */
    fun consume(context: Context, action: Int, x: Int, y: Int, width: Int, height: Int): TouchResult {
        if (swallowUntilUp) {
            if (action == CarTouch.UP || action == CarTouch.CANCEL) swallowUntilUp = false
            return TouchResult(consumed = true)
        }
        if (_state.value.phase != Phase.CHECK) return TouchResult()
        if (action == CarTouch.CANCEL) return TouchResult(consumed = true)
        if (action != CarTouch.DOWN) return TouchResult(consumed = true)
        swallowUntilUp = true
        return if (IccoaWelcomeTarget.contains(x, y, width, height)) {
            val stored = complete(context)
            DiagLog.i("ICCOA", "First-touch center check accepted; saved=$stored")
            TouchResult(consumed = true, completed = stored)
        } else {
            _state.value = State(Phase.FOUR_POINTS)
            CarTouchCalibration.begin(width, height)
            DiagLog.i("ICCOA", "First-touch center mismatch: entering four-point calibration")
            TouchResult(consumed = true)
        }
    }

    /** Existing four-point engine already stores the receiver-scoped quad. */
    fun completeFour(context: Context, width: Int, height: Int): Boolean {
        if (_state.value.phase != Phase.FOUR_POINTS ||
            CarTouchCalibration.state.value.step != 0 ||
            !CarTouchCalibration.hasVerifiedQuad(context, width, height)) return false
        val stored = complete(context)
        if (stored) {
            swallowUntilUp = true
            app.ft.FTApp.instance.prefs.mirrorUseCalibration = true
            DiagLog.i("ICCOA", "Four-point touch correction saved for verified vehicle")
        }
        return stored
    }

    private fun complete(context: Context): Boolean {
        val key = IccoaCalibrationScope.key("welcome") ?: return false
        val ok = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(key, true).commit()
        if (ok) _state.value = State()
        return ok
    }

    /** A lost connection NEVER erases a successful first-time check. */
    fun cancel() {
        swallowUntilUp = false
        _state.value = State()
    }

    /** Explicit forget resets the last authenticated receiver's welcome and quad. */
    fun forget(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val key = prefs.getString(LAST, null) ?: return false
        if (!Regex("^verified_[0-9a-f]{64}:welcome$").matches(key)) return false
        val prefix = key.removeSuffix("welcome")
        val quad = context.getSharedPreferences("ft-iccoa-touch", Context.MODE_PRIVATE)
        val edit = quad.edit()
        quad.all.keys.filter { it.startsWith(prefix) }.forEach(edit::remove)
        val q = edit.commit()
        val p = prefs.edit().remove(key).remove(LAST).commit()
        cancel()
        DiagLog.i("ICCOA", "Owner forgot verified car touch profile; success=${p && q}")
        return p && q
    }
}
