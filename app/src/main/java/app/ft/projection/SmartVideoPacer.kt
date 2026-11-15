package app.ft.projection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Session-local video output pacing policy. It never gates touch, audio, GPS or
 * upstream Android Auto H.264. Time comes from a monotonic clock.
 *
 * "Fresh" means a new SurfaceTexture buffer, NOT changed picture pixels.
 * Therefore the quiet cruise rate is conservative and STATIC is allowed only
 * after the source stops delivering buffers.
 */
internal class SmartVideoPacer(
    private val enabled: Boolean,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    enum class Mode { OFF, ACTIVE, CRUISE, STATIC, THERMAL }
    data class State(val fps: Int, val mode: Mode)

    private var requestedFps = 30
    private var thermalCeiling = 0
    private var lastInputMs = clockMs()
    private var lastFreshMs = lastInputMs
    private var hasFresh = false

    fun setRequestedFps(fps: Int) { requestedFps = fps.coerceIn(1, 60) }
    fun setThermalCeiling(fps: Int) { thermalCeiling = if (fps > 0) fps.coerceIn(1, 60) else 0 }
    fun onInput() { lastInputMs = clockMs() }
    fun onFreshBuffer() {
        hasFresh = true
        lastFreshMs = clockMs()
    }

    fun state(): State {
        val now = clockMs()
        val baseMode = when {
            !enabled -> Mode.OFF
            now - lastInputMs < CRUISE_AFTER_MS -> Mode.ACTIVE
            hasFresh && now - lastFreshMs >= STATIC_AFTER_MS -> Mode.STATIC
            else -> Mode.CRUISE
        }
        val target = when (baseMode) {
            Mode.CRUISE -> minOf(requestedFps, CRUISE_FPS)
            Mode.STATIC -> minOf(requestedFps, STATIC_FPS)
            else -> requestedFps
        }
        val constrained = if (thermalCeiling > 0) minOf(target, thermalCeiling) else target
        return State(constrained, if (thermalCeiling in 1 until target) Mode.THERMAL else baseMode)
    }

    companion object {
        const val CRUISE_AFTER_MS = 45_000L
        const val STATIC_AFTER_MS = 5_000L
        const val CRUISE_FPS = 15
        const val STATIC_FPS = 5
    }
}

/** Only the first genuinely fresh post-touch buffer bypasses output pacing. */
internal class UrgentFreshFrame(
    private val clockNs: () -> Long = System::nanoTime
) {
    private var untilNs = Long.MIN_VALUE

    fun arm(windowMs: Long = 600L) {
        untilNs = clockNs() + windowMs.coerceIn(1L, 1_000L) * 1_000_000L
    }

    fun takeOnFreshBuffer(): Boolean {
        val deadline = untilNs
        if (deadline == Long.MIN_VALUE) return false
        untilNs = Long.MIN_VALUE
        return clockNs() <= deadline
    }

    fun cancel() { untilNs = Long.MIN_VALUE }
}

/** Human input priority is independent of the video FPS limit. */
internal object VideoTouchPriority {
    fun urgent(action: Int): Boolean = action == CarTouch.DOWN || action == CarTouch.POINTER_DOWN
}

/** Effective output rate, not a promise that every frame reached the receiver. */
internal object VideoPacingStatus {
    data class Effective(val fps: Int, val mode: SmartVideoPacer.Mode)
    private val _state = MutableStateFlow<Effective?>(null)
    val state: StateFlow<Effective?> = _state

    fun publish(fps: Int, mode: SmartVideoPacer.Mode) { _state.value = Effective(fps, mode) }
    fun clear() { _state.value = null }
}
