package app.ft.ui.car

import app.ft.core.VehicleLocation

/** Presentation-only input selection; never affects AA location or ICCOA wire. */
internal object CarWidgetReadings {
    fun phoneSpeedKmh(phoneKmh: Float, preview: Boolean): Float? {
        val value = if (phoneKmh < 0f && preview) 60f else phoneKmh
        return value.takeIf { it.isFinite() && it in 0f..300f }
    }

    /** The 1-second UI tick can precede an incoming event by under a second. */
    fun displayNow(uiNowMs: Long, vehicle: VehicleLocation?): Long =
        maxOf(uiNowMs, vehicle?.receivedAtMs ?: uiNowMs)
}
