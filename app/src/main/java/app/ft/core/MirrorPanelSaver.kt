package app.ft.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Lowest system brightness for the *physical phone panel* during projection.
 * This is NOT an overlay: an overlay would also be captured by MediaProjection.
 * Screen power-off is intentionally not assumed safe for live phone mirroring.
 */
object MirrorPanelSaver {
    private const val STORE = "ft-mirror-panel-restore"
    private const val ACTIVE = "saved"
    private const val OLD_MODE = "mode"
    private const val OLD_LEVEL = "level"
    private const val DIM_LEVEL = 1
    private val owners = linkedSetOf<String>()
    private val touchWindow = MirrorTouchDimWindow()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingDim: Runnable? = null
    private var temporarilyBright = false
    private var pendingMonitorCheck: Runnable? = null
    private var suspendedForSession = false
    @get:Synchronized val isActive: Boolean get() = owners.isNotEmpty()

    private fun stopMonitorCheck() {
        pendingMonitorCheck?.let(handler::removeCallbacks)
        pendingMonitorCheck = null
    }

    /** Do not leave a phone at minimum brightness without a proven touch
     *  wake channel. A working video/capture path is not enough.
     */
    @Synchronized private fun restoreIfTouchUnavailable(context: Context) {
        pendingMonitorCheck = null
        if (owners.isEmpty()) return
        if (app.ft.FTTouchService.enabled ||
            app.ft.supercast.SuperCast.physicalTouchMonitorActive) return
        DiagLog.w("MirrorPanel",
            "no working phone touch wake source; restoring brightness and suspending dim")
        owners.clear()
        cancelDimTimer()
        touchWindow.stop()
        temporarilyBright = false
        suspendedForSession = true
        app.ft.supercast.SuperCast.later {
            app.ft.supercast.SuperCast.monitorPhoneTouches(false)
        }
        restore(context.applicationContext)
    }

    /** The helper may lose /dev/input permission after startup. */
    @Synchronized fun monitorUnavailable(context: Context) {
        if (owners.isNotEmpty() && !app.ft.FTTouchService.enabled)
            restoreIfTouchUnavailable(context)
    }


    private fun cancelDimTimer() {
        pendingDim?.let { handler.removeCallbacks(it) }
        pendingDim = null
    }

    private fun scheduleDim(context: Context) {
        cancelDimTimer()
        val delay = touchWindow.remainingMs(SystemClock.uptimeMillis()) ?: return
        val callback = Runnable { dimAfterIdle(context.applicationContext) }
        pendingDim = callback
        handler.postDelayed(callback, delay)
    }

    @Synchronized private fun dimAfterIdle(context: Context) {
        pendingDim = null
        if (owners.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (!touchWindow.isDue(now)) {
            scheduleDim(context)
            return
        }
        if (temporarilyBright && Settings.System.canWrite(context)) {
            val ok = runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, DIM_LEVEL)
            }.getOrDefault(false)
            if (ok) {
                temporarilyBright = false
                touchWindow.darkened()
                DiagLog.i("MirrorPanel", "phone screen re-dimmed after 30s without physical touch")
            } else DiagLog.w("MirrorPanel", "Android did not accept the timed brightness reduction")
        }
    }

    /** Physical phone touch from shell input monitoring, or Accessibility fallback. No coordinates. */
    @Synchronized fun onPhysicalTouch(context: Context) {
        val c = context.applicationContext
        if (owners.isEmpty() || !Settings.System.canWrite(c)) return
        val prefs = c.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ACTIVE, false)) return
        touchWindow.touched(SystemClock.uptimeMillis())
        if (!temporarilyBright) {
            val original = prefs.getInt(OLD_LEVEL, 128).coerceIn(1, 255)
            val ok = runCatching {
                // Leave auto-brightness disabled until mirroring ends; only the level changes.
                Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, original)
            }.getOrDefault(false)
            if (!ok) {
                DiagLog.w("MirrorPanel", "Android refused temporary normal brightness")
                return
            }
            temporarilyBright = true
            DiagLog.i("MirrorPanel", "physical phone touch temporarily restored brightness")
        }
        scheduleDim(c)
    }

    /** Stale snapshots survive process death and are repaired on the next FT startup. */
    @Synchronized fun recover(context: Context) {
        if (owners.isEmpty()) restore(context.applicationContext)
    }

    @Synchronized fun begin(context: Context, source: String): Boolean {
        val c = context.applicationContext
        if (!app.ft.FTApp.instance.prefs.mirrorDimPhone || suspendedForSession) return false
        if (!Settings.System.canWrite(c)) {
            DiagLog.w("MirrorPanel", "minimum brightness needs Modify system settings permission")
            return false
        }
        if (source in owners) return true
        if (owners.isEmpty()) {
            val prefs = c.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            if (prefs.getBoolean(ACTIVE, false)) {
                restore(c)
                // Never replace an unrecovered original snapshot with today's dimmed values.
                if (prefs.getBoolean(ACTIVE, false)) return false
            }
            val oldMode = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val oldLevel = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            if (!prefs.edit().putBoolean(ACTIVE, true).putInt(OLD_MODE, oldMode)
                    .putInt(OLD_LEVEL, oldLevel).commit()) {
                DiagLog.w("MirrorPanel", "could not commit brightness recovery snapshot; no changes made")
                return false
            }
            val dim = runCatching {
                Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) &&
                Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, DIM_LEVEL)
            }.getOrDefault(false)
            if (!dim) {
                DiagLog.w("MirrorPanel", "Android refused minimum brightness")
                restore(c)
                return false
            }
            DiagLog.i("MirrorPanel", "phone display brightness temporarily minimized during mirror")
        }
        owners.add(source)
        if (owners.size == 1) {
            cancelDimTimer()
            touchWindow.start()
            temporarilyBright = false
            app.ft.supercast.SuperCast.later {
                app.ft.supercast.SuperCast.monitorPhoneTouches(true)
            }
            stopMonitorCheck()
            val callback = Runnable { restoreIfTouchUnavailable(c) }
            pendingMonitorCheck = callback
            handler.postDelayed(callback, 3_500L)
        }
        return true
    }

    @Synchronized fun end(context: Context, source: String) {
        owners.remove(source)
        if (owners.isEmpty()) {
            suspendedForSession = false
            stopMonitorCheck()
            cancelDimTimer()
            touchWindow.stop()
            temporarilyBright = false
            app.ft.supercast.SuperCast.later { app.ft.supercast.SuperCast.monitorPhoneTouches(false) }
            restore(context.applicationContext)
        }
    }

    private fun restore(context: Context) {
        if (owners.isEmpty()) {
            stopMonitorCheck()
            cancelDimTimer()
            touchWindow.stop()
            temporarilyBright = false
        }
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ACTIVE, false)) return
        if (!Settings.System.canWrite(context)) {
            DiagLog.w("MirrorPanel", "cannot recover brightness until Modify system settings is restored")
            return
        }
        val brightness = prefs.getInt(OLD_LEVEL, 128)
        val mode = prefs.getInt(OLD_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val levelOK = runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        }.getOrDefault(false)
        val modeOK = runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        }.getOrDefault(false)
        if (levelOK && modeOK && prefs.edit().clear().commit()) {
            DiagLog.i("MirrorPanel", "phone brightness and automatic brightness mode restored")
        } else DiagLog.w("MirrorPanel", "brightness restoration incomplete; saved for retry on next launch")
    }
}
