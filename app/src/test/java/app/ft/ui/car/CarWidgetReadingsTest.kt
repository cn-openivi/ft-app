package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.VehicleLocation
import org.junit.Assert.*
import org.junit.Test

class CarWidgetReadingsTest {
    private fun phone(t: Long) = CarInfo.PhoneGpsFix(
        latitude = 55.55, longitude = 37.56, speedKmh = 26f,
        bearingDegrees = 231f, receivedAtMs = t
    )
    private fun car(t: Long, speed: Float = 25.2f) =
        VehicleLocation("ICCOA", t, positionAtMs = t,
            latitude = 55.55207, longitude = 37.55462, speedKmh = speed)

    @Test fun phoneSpeedIsUsedBeforeCarReacquisitionHysteresis() {
        val selected = CarGpsSourceSelector().select(
            car(10_000L), phone(10_000L), 10_000L)
        assertEquals("PHONE", CarWidgetReadings.sourceLabel(selected))
        assertEquals(26f, CarWidgetReadings.speedKmh(selected, false)!!, 0f)
    }

    @Test fun carHoldsShortGpsGapThenSwitchesToPhoneAfterSevenSeconds() {
        val selector = CarGpsSourceSelector()
        for (t in 10_000L..17_000L step 1_000L)
            selector.select(car(t), phone(t), t)
        val held = selector.select(null, phone(20_000L), 20_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, held.source)
        assertEquals("ICCOA · HOLD 3s", CarWidgetReadings.sourceLabel(held))
        assertEquals(25.2f, CarWidgetReadings.speedKmh(held, false)!!, 0f)
        val fallback = selector.select(null, phone(24_000L), 24_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, fallback.source)
        assertEquals("PHONE", CarWidgetReadings.sourceLabel(fallback))
        assertEquals(26f, CarWidgetReadings.speedKmh(fallback, false)!!, 0f)
    }

    @Test fun uiTickLagCannotMakeIncomingFixLookOld() {
        val car = car(10_900L)
        val current = CarWidgetReadings.displayNow(10_000L, car, phone(10_800L))
        assertEquals(10_900L, current)
    }

    @Test fun invalidSelectedPhoneSpeedIsNotDisplayedAsRealMeasurement() {
        val selector = CarGpsSourceSelector()
        val invalid = phone(10_000L).copy(speedKmh = Float.NaN)
        val reading = selector.select(null, invalid, 10_000L)
        assertNull(CarWidgetReadings.speedKmh(reading, false))
        assertEquals(60f, CarWidgetReadings.speedKmh(reading, true)!!, 0f)
    }

    @Test fun unavailableGpsDoesNotInventLocationOrSpeed() {
        val selector = CarGpsSourceSelector()
        val reading = selector.select(null, null, 10_000L)
        assertNull(reading.latitude)
        assertNull(reading.longitude)
        assertNull(CarWidgetReadings.speedKmh(reading, false))
    }
}
