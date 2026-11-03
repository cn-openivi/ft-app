package app.ft.ui.car

import app.ft.core.VehicleLocation
import org.junit.Assert.*
import org.junit.Test

class CarWidgetReadingsTest {
    @Test fun phoneSpeedNeverAutomaticallySwitchesToQuantizedOemSpeed() {
        // Car speed is intentionally not an input to this selector.
        assertEquals(26f, CarWidgetReadings.phoneSpeedKmh(26f, false)!!, 0f)
        assertEquals(27f, CarWidgetReadings.phoneSpeedKmh(27f, false)!!, 0f)
        assertNull(CarWidgetReadings.phoneSpeedKmh(-1f, false))
        assertEquals(60f, CarWidgetReadings.phoneSpeedKmh(-1f, true)!!, 0f)
        assertNull(CarWidgetReadings.phoneSpeedKmh(Float.NaN, false))
        assertNull(CarWidgetReadings.phoneSpeedKmh(320f, false))
    }

    @Test fun freshFixIsNotStaleZeroSecondsFromUiTickLag() {
        val car = VehicleLocation("ICCOA", receivedAtMs=10_900L,
            positionAtMs=10_900L, latitude=55.55207, longitude=37.55462)
        val now = CarWidgetReadings.displayNow(10_000L, car)
        assertEquals(10_900L, now)
        assertTrue(car.positionFresh(now))
    }

    @Test fun genuinelyOldPositionStillShowsStale() {
        val car = VehicleLocation("ICCOA", receivedAtMs=20_000L,
            positionAtMs=15_000L, latitude=55.55207, longitude=37.55462)
        val now = CarWidgetReadings.displayNow(19_400L, car)
        assertEquals(20_000L, now)
        assertFalse(car.positionFresh(now))
        assertEquals(27_000L, CarWidgetReadings.displayNow(27_000L, car))
    }
}
