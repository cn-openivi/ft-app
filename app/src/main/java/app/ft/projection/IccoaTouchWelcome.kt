package app.ft.projection

import android.content.Context
import app.ft.core.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Target follows the real virtual-display content geometry. */
internal object IccoaWelcomeTarget {
    // Large visible targets. Center alone cannot prove that edge mapping works.
    fun radiusPx(width: Int, height: Int): Float =
        maxOf(64f, minOf(width, height) * 0.065f).coerceAtMost(96f)

    fun rightX(width: Int): Int = (width * 0.78f).toInt()
    fun rightY(height: Int): Int = (height * 0.72f).toInt()

    private fun near(x: Int, y: Int, width: Int, height: Int, cx: Int, cy: Int): Boolean {
        if (width < 200 || height < 150 || x !in 0 until width || y !in 0 until height)
            return false
        val dx = x - cx.toDouble()
        val dy = y - cy.toDouble()
        val r = radiusPx(width, height)
        return dx * dx + dy * dy <= r * r
    }
    fun contains(x: Int, y: Int, width: Int, height: Int): Boolean =
        near(x, y, width, height, width / 2, height / 2)

    fun containsRightProbe(x: Int, y: Int, width: Int, height: Int): Boolean =
        near(x, y, width, height, rightX(width), rightY(height))
}

/** Displayed center OK verifies mapping; calibration is a separate explicit fallback. */
internal object IccoaTouchWelcome {
    enum class Phase { NONE, CHECK, FOUR_POINTS }
    data class State(
        val phase: Phase = Phase.NONE,
        /** Visible failure of independent center-hit verification, never silent success. */
        val centerMismatch: Boolean = false,
        val centerChecked: Boolean = false,
        val failedAttempts: Int = 0
    )
    data class TouchResult(val consumed: Boolean = false, val completed: Boolean = false)

    private const val PREF = "ft-iccoa-touch-welcome"
    private const val LAST = "last_verified_welcome"
    private const val SPATIAL_SUFFIX = ":spatial-check-v1"
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
        // A pre-187 'welcome verified' flag is NOT proof of how the current
        // input connection interprets x0/y0. If no verified domain was saved,
        // show the center check again, rather than silently using PANEL_PIXELS.
        val domain = app.ft.iccoa.IccoaInputCoordinateSpace.remembered(context)
        val needsCheck = app.ft.iccoa.IccoaInputCoordinateSpace.requiresWelcome(
            prefs.getBoolean(key, false) &&
                prefs.getBoolean(key + SPATIAL_SUFFIX, false), domain)
        _state.value = State(if (needsCheck) Phase.CHECK else Phase.NONE)
        DiagLog.i("ICCOA", "Touch welcome required=${awaiting}; " +
            "receiver verified; cached UIBC domain=${domain.name}")
    }

    /** Validate center AND an off-center marker before persisting PASS. */
    fun consume(context: Context, action: Int, x: Int, y: Int, width: Int, height: Int): TouchResult {
        if (swallowUntilUp) {
            if (action == CarTouch.UP || action == CarTouch.CANCEL) swallowUntilUp = false
            return TouchResult(consumed = true)
        }
        val previous = _state.value
        if (previous.phase != Phase.CHECK) return TouchResult()
        if (action == CarTouch.CANCEL) return TouchResult(consumed = true)
        if (action != CarTouch.DOWN) return TouchResult(consumed = true)
        swallowUntilUp = true

        val edge = previous.centerChecked
        val hit = if (edge)
            IccoaWelcomeTarget.containsRightProbe(x, y, width, height)
        else IccoaWelcomeTarget.contains(x, y, width, height)
        if (hit) {
            if (!edge) {
                // A correct center can still hide an offset/scale error elsewhere.
                _state.value = State(phase = Phase.CHECK, centerChecked = true)
                DiagLog.i("ICCOA", "UIBC_CENTER_PROBE=PASS; spatial_probe=PENDING; not verified")
                return TouchResult(consumed = true)
            }
            val stored = complete(context)
            DiagLog.i("ICCOA", "UIBC_CENTER_AND_EDGE_VERIFIED=" +
                (if (stored) "PASS" else "FAIL") + "; saved=$stored")
            return TouchResult(consumed = true, completed = stored)
        }

        val expectedX = if (edge) IccoaWelcomeTarget.rightX(width) else width / 2
        val expectedY = if (edge) IccoaWelcomeTarget.rightY(height) else height / 2
        val dxPermille = (x - expectedX).toLong() * 1000L / width
        val dyPermille = (y - expectedY).toLong() * 1000L / height
        val misses = previous.failedAttempts + 1
        DiagLog.w("ICCOA",
            (if (edge) "UIBC_EDGE_VALIDATION" else "UIBC_CENTER_VALIDATION") + "=FAIL " +
            "dx_per_mille=$dxPermille dy_per_mille=$dyPermille " +
            "attempt=$misses; receiver NOT verified")
        if (misses >= 2) {
            // One wrong tap is not enough to force a calibration. A second
            // verified miss makes the existing HU 4-point fallback reachable
            // without opening settings on a phone.
            _state.value = State(phase = Phase.FOUR_POINTS, centerMismatch = true)
            CarTouchCalibration.begin(width, height)
            DiagLog.w("ICCOA", "UIBC_SPATIAL_VALIDATION=FAIL; HU four-point fallback started")
        } else {
            _state.value = previous.copy(centerMismatch = true, failedAttempts = misses)
        }
        return TouchResult(consumed = true)
    }

    /** Phone-side fallback or automatic fallback after two failed HU attempts. */
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
            .edit().putBoolean(key, true).putBoolean(key + SPATIAL_SUFFIX, true).commit()
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
        val mode = app.ft.iccoa.IccoaInputCoordinateSpace.forgetReceiver(context, key)
        val p = prefs.edit().remove(key).remove(key + SPATIAL_SUFFIX).remove(LAST).commit()
        cancel()
        DiagLog.i("ICCOA", "Owner forgot verified car touch profile; success=${p && q && mode}")
        return p && q && mode
    }
}
