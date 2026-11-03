package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.GpsSourceMode
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

    @Test fun explicitlySelectedPhoneSpeedIsUsed() {
        val selected = CarGpsSourceSelector().select(
            car(10_000L), phone(10_000L), 10_000L, GpsSourceMode.PHONE)
        assertEquals("PHONE", CarWidgetReadings.sourceLabel(selected))
        assertEquals(26f, CarWidgetReadings.speedKmh(selected, false)!!, 0f)
    }

    @Test fun carHoldsLastFixWithoutSwitchingToPhoneOnOutage() {
        val selector = CarGpsSourceSelector()
        selector.select(car(10_000L), phone(10_000L), 10_000L, GpsSourceMode.CAR)
        val selected = selector.select(null, phone(21_000L), 21_000L,
            GpsSourceMode.CAR)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, selected.source)
        assertEquals("ICCOA · HOLD 11s", CarWidgetReadings.sourceLabel(selected))
        assertEquals(25.2f, CarWidgetReadings.speedKmh(selected, false)!!, 0f)
    }

    @Test fun uiTickLagCannotMakeIncomingFixLookOld() {
        val car = car(10_900L)
        val current = CarWidgetReadings.displayNow(10_000L, car, phone(10_800L))
        assertEquals(10_900L, current)
    }

    @Test fun invalidSelectedPhoneSpeedIsNotDisplayedAsRealMeasurement() {
        val selector = CarGpsSourceSelector()
        val invalid = phone(10_000L).copy(speedKmh = Float.NaN)
        val reading = selector.select(null, invalid, 10_000L, GpsSourceMode.PHONE)
        assertNull(CarWidgetReadings.speedKmh(reading, false))
        assertEquals(60f, CarWidgetReadings.speedKmh(reading, true)!!, 0f)
    }

    @Test fun unavailableSelectedGpsDoesNotInventLocationOrSpeed() {
        val selector = CarGpsSourceSelector()
        for (source in GpsSourceMode.entries) {
            val reading = selector.select(null, null, 10_000L, source)
            assertNull(reading.latitude)
            assertNull(reading.longitude)
            assertNull(CarWidgetReadings.speedKmh(reading, false))
        }
    }
}
