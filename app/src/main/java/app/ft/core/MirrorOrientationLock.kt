package app.ft.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import app.ft.FTApp
import app.ft.FTTouchService

/** Opt-in global device rotation control while mirroring. Restores the user's values on stop. */
object MirrorOrientationLock {
    private const val STORE = "ft-mirror-rotation-restore"
    private val owners = linkedMapOf<String, String>()
    private var homePackage = ""
    private var applied: Int? = null

    @Synchronized fun recover(context: Context) {
        if (owners.isEmpty()) restore(context.applicationContext)
    }

    @Synchronized fun begin(context: Context, source: String, targetPackage: String, isHome: Boolean): Boolean {
        val c = context.applicationContext
        if (!FTApp.instance.prefs.mirrorLandscapeApps) return false
        if (!Settings.System.canWrite(c) || !FTTouchService.enabled) {
            DiagLog.w("MirrorOrientation", "orientation lock needs Modify system settings and FT touch accessibility")
            return false
        }
        if (owners.isEmpty()) {
            val savedPrefs = c.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            if (savedPrefs.getBoolean("saved", false)) {
                restore(c)
                if (savedPrefs.getBoolean("saved", false)) return false
            }
            val cr = c.contentResolver
            val auto = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
            val rotation = Settings.System.getInt(cr, Settings.System.USER_ROTATION, 0)
            if (!c.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                    .putBoolean("saved", true).putInt("auto", auto).putInt("rotation", rotation).commit()) {
                DiagLog.w("MirrorOrientation", "could not save previous rotation settings")
                return false
            }
            homePackage = c.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName.orEmpty()
            if (homePackage.isBlank()) {
                restore(c)
                return false
            }
        }
        owners[source] = if (isHome) homePackage else targetPackage
        apply(c, if (isHome) MirrorOrientationPolicy.PORTRAIT else MirrorOrientationPolicy.LANDSCAPE)
        return true
    }

    @Synchronized fun foreground(context: Context, eventType: Int, packageName: String?) {
        if (owners.isEmpty() || eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val target = owners.values.lastOrNull() ?: return
        val wanted = MirrorOrientationPolicy.desired(target, homePackage, packageName) ?: return
        apply(context.applicationContext, wanted)
    }

    private fun apply(context: Context, wanted: Int) {
        if (applied == wanted || !Settings.System.canWrite(context)) return
        val cr = context.contentResolver
        val ok = runCatching {
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) &&
                Settings.System.putInt(cr, Settings.System.USER_ROTATION, wanted)
        }.getOrDefault(false)
        if (ok) {
            applied = wanted
            DiagLog.i("MirrorOrientation", if (wanted == 0) "phone launcher portrait" else "mirrored app landscape")
        } else DiagLog.w("MirrorOrientation", "Android did not accept orientation lock")
    }

    @Synchronized fun end(context: Context, source: String) {
        owners.remove(source)
        if (owners.isEmpty()) restore(context.applicationContext)
    }

    @Synchronized fun stopAll(context: Context) {
        owners.clear()
        restore(context.applicationContext)
    }

    private fun restore(context: Context) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("saved", false)) { applied = null; return }
        if (!Settings.System.canWrite(context)) return
        val cr = context.contentResolver
        val old = prefs.getInt("rotation", 0)
        val auto = prefs.getInt("auto", 1)
        val r = runCatching { Settings.System.putInt(cr, Settings.System.USER_ROTATION, old) }.getOrDefault(false)
        val a = runCatching { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, auto) }.getOrDefault(false)
        if (r && a && prefs.edit().clear().commit()) {
            applied = null
            DiagLog.i("MirrorOrientation", "user rotation preference restored")
        } else DiagLog.w("MirrorOrientation", "rotation restore incomplete; retry on next FT startup")
    }
}
