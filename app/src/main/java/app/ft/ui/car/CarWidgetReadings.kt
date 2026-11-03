package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.VehicleLocation

/** Presentation-only helpers; Android Auto GPS delivery is independent. */
internal object CarWidgetReadings {
    fun displayNow(
        uiNowMs: Long,
        vehicle: VehicleLocation?,
        phone: CarInfo.PhoneGpsFix? = null
    ): Long = maxOf(
        uiNowMs, vehicle?.receivedAtMs ?: uiNowMs, phone?.receivedAtMs ?: uiNowMs
    )

    fun speedKmh(reading: CarGpsSourceSelector.Reading, preview: Boolean): Float? {
        val selected = reading.speedKmh
        return when {
            selected != null && selected.isFinite() && selected in 0f..300f -> selected
            preview -> 60f
            else -> null
        }
    }

    /** No STALE=0s labels. A short GPS pause does not switch the source. */
    fun sourceLabel(reading: CarGpsSourceSelector.Reading): String =
        if (reading.lastPacketAgeMs >= 2_000L)
            "${reading.label} · HOLD ${reading.lastPacketAgeMs / 1000L}s"
        else reading.label
}
