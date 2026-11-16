package app.ft.ui.car

import app.ft.dr.CarTunnelKalman
import org.junit.Assert.*
import org.junit.Test

class CarTunnelWidgetReadingsTest {
    private fun position(mode: CarTunnelKalman.Mode) = CarTunnelKalman.Estimate(
        latitude = 55.75582, longitude = 37.61729,
        courseDegrees = 90.0,
        modelStdMetres = 45.12,
        gpsReceiptGapMs = 10_000L,
        travelledModelMetres = 60.0,
        mode = mode,
        lastCarGpsAtMonoMs = 1_000L
    )

    @Test fun tunnelPredictionIsClearlyLabelledAsUnverifiedAndNeverAaGps() {
        val model = position(CarTunnelKalman.Mode.TUNNEL_COURSE_HELD)
        assertFalse(model.eligibleForAaSensorLocation)
        val chip = CarWidgetReadings.tunnelReadout(model)!!
        assertEquals("55.75582, 37.61729", chip.value)
        assertTrue(chip.description.contains("КАЛМАН"))
        assertTrue(chip.description.contains("ПРОГНОЗ"))
        assertTrue(chip.description.contains("±46 м"))
        assertTrue(chip.description.contains("НЕ GPS/AA"))
    }

    @Test fun liveGpsHasNoDuplicateModelChipButUnknownTunnelHeadingIsWarned() {
        assertNull(CarWidgetReadings.tunnelReadout(null))
        assertNull(CarWidgetReadings.tunnelReadout(
            position(CarTunnelKalman.Mode.GPS_OBSERVED)))
        assertNull(CarWidgetReadings.tunnelReadout(
            position(CarTunnelKalman.Mode.GPS_NO_COURSE)))
        val unknown = CarWidgetReadings.tunnelReadout(
            position(CarTunnelKalman.Mode.TUNNEL_NO_DIRECTION))!!
        assertTrue(unknown.description.contains("КУРС НЕИЗВЕСТЕН"))
    }
}
