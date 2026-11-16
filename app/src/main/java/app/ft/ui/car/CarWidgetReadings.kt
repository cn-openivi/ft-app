package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.VehicleLocation
import app.ft.dr.DrNavigationFix
import app.ft.dr.CarTunnelKalman
import java.util.Locale
import kotlin.math.ceil

/** FT-native rendering of the SAME authoritative location used by Android Auto. */
internal object CarWidgetReadings {
    fun displayNow(
        uiNowMs: Long,
        vehicle: VehicleLocation?,
        phone: CarInfo.PhoneGpsFix? = null,
        dr: DrNavigationFix? = null
    ): Long = maxOf(
        uiNowMs, vehicle?.receivedAtMs ?: uiNowMs,
        phone?.receivedAtMs ?: uiNowMs,
        dr?.producedAtMs ?: uiNowMs
    )

    /** Native display only: a modelled tunnel prediction is never AA GPS. */
    data class TunnelReadout(val value: String, val description: String)

    fun tunnelReadout(estimate: CarTunnelKalman.Estimate?): TunnelReadout? {
        if (estimate == null ||
            estimate.mode != CarTunnelKalman.Mode.TUNNEL_COURSE_HELD &&
            estimate.mode != CarTunnelKalman.Mode.TUNNEL_NO_DIRECTION ||
            !estimate.latitude.isFinite() || !estimate.longitude.isFinite() ||
            !estimate.modelStdMetres.isFinite() || estimate.modelStdMetres < 0.0 ||
            estimate.eligibleForAaSensorLocation) return null
        val value = String.format(Locale.US, "%.5f, %.5f",
            estimate.latitude, estimate.longitude)
        val uncertainty = ceil(estimate.modelStdMetres).toLong()
        val kind = if (estimate.mode == CarTunnelKalman.Mode.TUNNEL_COURSE_HELD)
            "КАЛМАН · ПРОГНОЗ" else "КАЛМАН · КУРС НЕИЗВЕСТЕН"
        return TunnelReadout(
            value = value,
            description = "$kind · ±${uncertainty} м · НЕ GPS/AA"
        )
    }

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
            CarGpsSourceSelector.Source.DR -> reading.dr?.speedKmh
            CarGpsSourceSelector.Source.UNKNOWN -> null
        }
        // The OEM CarLink rounds speed to integral m/s; below 1 m/s its
        // course is not a reliable direction-of-travel measurement.
        if (physicalSpeedKmh == null || !physicalSpeedKmh.isFinite() ||
            physicalSpeedKmh < 3.6f) return null
        return reading.headingDegrees?.takeIf { it.isFinite() && it in 0f..360f }
    }

    /** Display source and age directly, without an arbitrary GNSS loss threshold. */
    fun sourceLabel(reading: CarGpsSourceSelector.Reading): String = when (reading.source) {
        CarGpsSourceSelector.Source.UNKNOWN -> "NO VERIFIED POSITION"
        CarGpsSourceSelector.Source.PHONE ->
            if (reading.phone == null) "PHONE · NO FIX" else "PHONE · MANUAL"
        CarGpsSourceSelector.Source.DR -> reading.dr?.accuracyMetres?.let {
            "DR · last estimate ${reading.lastPacketAgeMs}ms ago · ±${kotlin.math.ceil(it).toLong()}m"
        } ?: "DR · last estimate"
        CarGpsSourceSelector.Source.ICCOA ->
            if (reading.lastPacketAgeMs > 0L)
                "ICCOA · last fix ${reading.lastPacketAgeMs}ms ago"
            else "ICCOA"
    }
}
