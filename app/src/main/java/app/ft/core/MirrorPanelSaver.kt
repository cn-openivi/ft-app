package app.ft.core

import android.content.Context
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

    /** Stale snapshots survive process death and are repaired on the next FT startup. */
    @Synchronized fun recover(context: Context) {
        if (owners.isEmpty()) restore(context.applicationContext)
    }

    @Synchronized fun begin(context: Context, source: String): Boolean {
        val c = context.applicationContext
        if (!app.ft.FTApp.instance.prefs.mirrorDimPhone) return false
        if (!Settings.System.canWrite(c)) {
            DiagLog.w("MirrorPanel", "minimum brightness needs Modify system settings permission")
            return false
        }
        if (source in owners) return true
        if (owners.isEmpty()) {
            restore(c) // recover any interrupted previous attempt before making another snapshot
            val prefs = c.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val oldMode = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val oldLevel = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            if (!prefs.edit().putBoolean(ACTIVE, true).putInt(OLD_MODE, oldMode)
                    .putInt(OLD_LEVEL, oldLevel).commit()) {
                DiagLog.w("MirrorPanel", "could not commit brightness recovery snapshot; no changes made")
                return false
            }
            val manual = Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val dim = manual && Settings.System.putInt(c.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, DIM_LEVEL)
            if (!dim) {
                DiagLog.w("MirrorPanel", "Android refused minimum brightness")
                restore(c)
                return false
            }
            DiagLog.i("MirrorPanel", "phone display brightness temporarily minimized during mirror")
        }
        owners.add(source)
        return true
    }

    @Synchronized fun end(context: Context, source: String) {
        owners.remove(source)
        if (owners.isEmpty()) restore(context.applicationContext)
    }

    private fun restore(context: Context) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ACTIVE, false)) return
        if (!Settings.System.canWrite(context)) {
            DiagLog.w("MirrorPanel", "cannot recover brightness until Modify system settings is restored")
            return
        }
        val brightness = prefs.getInt(OLD_LEVEL, 128)
        val mode = prefs.getInt(OLD_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val levelOK = Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        val modeOK = Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        if (levelOK && modeOK && prefs.edit().clear().commit()) {
            DiagLog.i("MirrorPanel", "phone brightness and automatic brightness mode restored")
        } else DiagLog.w("MirrorPanel", "brightness restoration incomplete; saved for retry on next launch")
    }
}
