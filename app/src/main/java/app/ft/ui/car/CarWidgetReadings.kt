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

    /**
     * Course-over-ground is not a compass reading: at a standstill the OEM
     * GPS reports headingRaw=0, which must not be presented as true north.
     * Use the fresh selected source's measured speed, NOT smoothed display
     * speed or a GearInfo frame. Preserve a genuine moving 0° course.
     */
    fun courseDegrees(reading: CarGpsSourceSelector.Reading): Float? {
        val physicalSpeedKmh = when (reading.source) {
            CarGpsSourceSelector.Source.ICCOA -> reading.car?.speedKmh
            CarGpsSourceSelector.Source.PHONE -> reading.phone?.speedKmh
            CarGpsSourceSelector.Source.DR, CarGpsSourceSelector.Source.UNKNOWN -> null
        }
        // The OEM CarLink rounds speed to integral m/s; below 1 m/s its
        // course is not a reliable direction-of-travel measurement.
        if (physicalSpeedKmh == null || !physicalSpeedKmh.isFinite() ||
            physicalSpeedKmh < 3.6f) return null
        return reading.headingDegrees?.takeIf { it.isFinite() && it in 0f..360f }
    }

    /** Provenance is explicit: never label unavailable GNSS as phone fallback. */
    fun sourceLabel(reading: CarGpsSourceSelector.Reading): String = when (reading.source) {
        CarGpsSourceSelector.Source.UNKNOWN -> "GPS LOST"
        CarGpsSourceSelector.Source.PHONE ->
            if (reading.phone == null) "PHONE · NO FIX" else "PHONE · MANUAL"
        CarGpsSourceSelector.Source.DR -> reading.dr?.accuracyMetres?.let {
            "DR estimated ±${kotlin.math.ceil(it).toLong()}m"
        } ?: "DR estimated"
        CarGpsSourceSelector.Source.ICCOA ->
            if (reading.lastPacketAgeMs >= 2_000L)
                "ICCOA · HOLD ${reading.lastPacketAgeMs / 1000L}s"
            else "ICCOA"
    }
}
