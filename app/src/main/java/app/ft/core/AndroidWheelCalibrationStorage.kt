package app.ft.core

import android.content.Context

/** No writes in a motion callback; commit only an accepted segment. */
internal class AndroidWheelCalibrationStorage(context: Context) :
    WheelCalibrationStore.Storage {
    private val prefs = context.applicationContext.getSharedPreferences(
        "ft-wheel-distance-calibration-v1", Context.MODE_PRIVATE
    )

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String): Boolean =
        prefs.edit().putString(key, value).commit()
}
