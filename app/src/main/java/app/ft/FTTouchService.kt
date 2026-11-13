package app.ft

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import app.ft.supercast.adb.WirelessPairingAutoCode
import app.ft.core.DiagLog

class FTTouchService : AccessibilityService() {
    companion object {
        @Volatile var instance: FTTouchService? = null
            private set
        val enabled: Boolean get() = instance != null
        private val _connected = kotlinx.coroutines.flow.MutableStateFlow(false)
        val connected: kotlinx.coroutines.flow.StateFlow<Boolean> = _connected
        private const val SEGMENT_MS = 32L
    }

    private var stroke: GestureDescription.StrokeDescription? = null
    private var target = 0
    private var downAt = 0L
    private var lastX = 0f
    private var lastY = 0f
    @Volatile private var lastInjectedAt = Long.MIN_VALUE

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        DiagLog.i("Touch", "accessibility touch injector connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        gone()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        gone()
        super.onDestroy()
    }

    private fun gone() {
        if (instance !== this) return
        app.ft.core.MirrorOrientationLock.stopAll(this)
        instance = null
        _connected.value = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null &&
            (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
             event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) &&
            (Build.VERSION.SDK_INT < 33 || event.displayId == Display.DEFAULT_DISPLAY) &&
            !app.ft.supercast.SuperCast.physicalTouchMonitorActive &&
            (lastInjectedAt == Long.MIN_VALUE || SystemClock.uptimeMillis() - lastInjectedAt > 1_000L)) {
            // Fallback only. It neither consumes touches nor reads screen content.
            app.ft.core.MirrorPanelSaver.onPhysicalTouch(this)
        }
        app.ft.core.MirrorOrientationLock.foreground(
            this, event?.eventType ?: 0, event?.packageName?.toString()
        )
        if (app.ft.supercast.adb.WirelessPairingService.currentPort() == null) return
        WirelessPairingAutoCode.onEvent(event, rootInActiveWindow, this)
    }
    override fun onInterrupt() = Unit

    fun inject(action: Int, x: Float, y: Float, displayId: Int = 0) {
        target = displayId
        when (action) {
            0 -> {
                lastX = x
                lastY = y
                downAt = SystemClock.uptimeMillis()
                val p = Path().apply { moveTo(x, y); lineTo(x, y) }
                stroke = GestureDescription.StrokeDescription(p, 0L, SEGMENT_MS, true).also { dispatch(it) }
            }
            2 -> {
                val previous = stroke ?: return
                if (lastX == x && lastY == y) return
                val p = Path().apply { moveTo(lastX, lastY); lineTo(x, y) }
                lastX = x
                lastY = y
                stroke = runCatching { previous.continueStroke(p, 0L, SEGMENT_MS, true) }
                    .getOrNull()?.also { dispatch(it) }
            }
            else -> {
                val previous = stroke
                val p = Path().apply { moveTo(lastX, lastY); lineTo(x, y) }
                val last = if (previous != null) {
                    runCatching { previous.continueStroke(p, 0L, SEGMENT_MS, false) }.getOrNull()
                } else {
                    GestureDescription.StrokeDescription(Path().apply { moveTo(x, y); lineTo(x, y) }, 0L, SEGMENT_MS, false)
                }
                stroke = null
                lastX = x
                lastY = y
                last?.let { dispatch(it) }
            }
        }
    }

    /** Best-effort local abandon: do not issue an Android UP or an accidental tap. */
    fun cancelStroke() { stroke = null }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        lastInjectedAt = SystemClock.uptimeMillis()
        val ok = runCatching {
            val b = GestureDescription.Builder().addStroke(s)
            if (target > 0 && Build.VERSION.SDK_INT >= 30) b.setDisplayId(target)
            dispatchGesture(b.build(), null, null)
        }.getOrDefault(false)
        if (!ok) stroke = null
    }

    private var atomicTapAccepted = 0L
    private var atomicTapCompleted = 0L
    private var atomicTapCancelled = 0L
    private var atomicTapRejected = 0L

    private fun logAtomicTapSummary(reason: String) {
        // No x/y, package, activity or other user input is collected.
        DiagLog.i("MirrorTouch", "$reason requests=$atomicTapAccepted completed=$atomicTapCompleted " +
            "cancelled=$atomicTapCancelled rejected=$atomicTapRejected")
    }

    /** A single, completed Accessibility gesture for a mirrored stationary touch. */
    fun tap(x: Float, y: Float, durationMs: Long = 50L) {
        lastInjectedAt = SystemClock.uptimeMillis()
        val duration = durationMs.coerceIn(60L, 1_200L)
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val result = runCatching {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration)).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    atomicTapCompleted++
                    if (atomicTapCompleted <= 3L ||
                        ((atomicTapCompleted and (atomicTapCompleted - 1L)) == 0L))
                        logAtomicTapSummary("completed")
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    atomicTapCancelled++
                    logAtomicTapSummary("cancelled")
                }
            }, null)
        }
        if (result.getOrDefault(false)) atomicTapAccepted++
        else {
            atomicTapRejected++
            logAtomicTapSummary("rejected")
        }
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
}
