package app.ft.projection

import android.content.Context
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Target follows the real virtual-display content geometry. */
internal object IccoaWelcomeTarget {
    // A precise first-touch check, slightly larger than the four-point circles.
    // One radius function owns both visible circle and incoming UIBC hit-test.
    fun radiusPx(width: Int, height: Int): Float =
        maxOf(30f, minOf(width, height) * 0.025f).coerceAtMost(38f)

    fun contains(x: Int, y: Int, width: Int, height: Int): Boolean {
        if (width < 200 || height < 150 || x !in 0 until width || y !in 0 until height) return false
        val r = radiusPx(width, height)
        val dx = x - width / 2.0
        val dy = y - height / 2.0
        return dx * dx + dy * dy <= r * r
    }
}

/** Displayed center OK verifies mapping; calibration is a separate explicit fallback. */
internal object IccoaTouchWelcome {
    enum class Phase { NONE, CHECK, FOUR_POINTS }
    data class State(
        val phase: Phase = Phase.NONE,
        /** Visible failure of independent center-hit verification, never silent success. */
        val centerMismatch: Boolean = false
    )
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
            // The OK target is a VERIFICATION indicator, never just an
            // onboarding button. A miss means mapping verification FAILED;
            // do not mark the receiver verified or start calibration.
            _state.value = State(Phase.CHECK, centerMismatch = true)
            val errorXPermille = (x.toLong() * 1000L / width - 500L)
            val errorYPermille = (y.toLong() * 1000L / height - 500L)
            // Only the known center-check target is logged. This is a bounded
            // displacement from a public UI marker, not free-form touch history.
            DiagLog.w("ICCOA",
                "UIBC_CENTER_VALIDATION=FAIL dx_per_mille=${errorXPermille} " +
                "dy_per_mille=${errorYPermille} center=${width}x${height}; " +
                "not verified; no automatic calibration")
            TouchResult(consumed = true)
        }
    }

    /** Explicit phone-side fallback only; never triggered by a single wrong tap. */
    fun requestFourPoints(width: Int, height: Int): Boolean {
        if (_state.value.phase != Phase.CHECK) return false
        _state.value = State(Phase.FOUR_POINTS)
        CarTouchCalibration.begin(width, height)
        DiagLog.i("ICCOA", "Owner requested optional four-point touch correction from phone")
        return true
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
        quad.all.keys.filter { it.startsWith(prefix) }.forEach { edit.remove(it) }
        val q = edit.commit()
        val p = prefs.edit().remove(key).remove(LAST).commit()
        cancel()
        DiagLog.i("ICCOA", "Owner forgot verified car touch profile; success=${p && q}")
        return p && q
    }
}
