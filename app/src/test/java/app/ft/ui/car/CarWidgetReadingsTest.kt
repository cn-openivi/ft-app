package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.GpsSourceMode
import app.ft.core.VehicleLocation
import app.ft.dr.DrNavigationFix
import org.junit.Assert.*
import org.junit.Test

class CarWidgetReadingsTest {
    private fun phone(t: Long, speed: Float = 26f) = CarInfo.PhoneGpsFix(
        latitude = 55.55, longitude = 37.56, speedKmh = speed,
        bearingDegrees = 231f, receivedAtMs = t,
        provider = "gps", horizontalAccuracyMetres = 10f, fixAgeNanosAtReceipt = 0L
    )
    private fun car(t: Long, speed: Float = 25.2f) =
        VehicleLocation("ICCOA", t, positionAtMs = t,
            latitude = 55.55207, longitude = 37.55462,
            speedKmh = speed, headingDegrees = 0f,
            coordinatesUnit = "ANDROID_LOCATION_WGS84")
    private fun dr(t: Long) = DrNavigationFix(55.55, 37.56, 14.5, 120.0,
        GpsSourceMode.CAR, t, true, true)

    @Test fun carSourceNeverSwitchesToPhoneAfterArbitraryTime() {
        val selector = CarGpsSourceSelector()
        val carFix = car(10_000L)
        val initial = selector.select(carFix, phone(10_000L), 10_000L)
        assertEquals("ICCOA", CarWidgetReadings.sourceLabel(initial))
        val historical = selector.select(carFix, phone(20_000L), 20_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, historical.source)
        assertEquals("ICCOA · last fix 10000ms ago",
            CarWidgetReadings.sourceLabel(historical))
        assertNull(historical.phone)
    }

    @Test fun theFirstVerifiedDrPredictionFillsTheGpsGapImmediately() {
        val selector = CarGpsSourceSelector()
        val reading = selector.select(car(10_000L), phone(10_080L),
            10_080L, drFix = dr(10_080L))
        assertEquals(CarGpsSourceSelector.Source.DR, reading.source)
        assertEquals("DR · last estimate 0ms ago · ±15m", CarWidgetReadings.sourceLabel(reading))
        assertNull(CarWidgetReadings.speedKmh(reading, false))
    }

    @Test fun aRecordedGpsPositionIsNeverCalledPhone() {
        val historical = CarGpsSourceSelector().select(
            car(10_000L), phone(90_000L), 90_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, historical.source)
        assertEquals("ICCOA · last fix 80000ms ago",
            CarWidgetReadings.sourceLabel(historical))
    }

    @Test fun manualPhoneIsLabeledAndInvalidProviderIsNotForwarded() {
        val selector = CarGpsSourceSelector()
        val valid = selector.select(null, phone(10_000L), 10_000L,
            manualPhoneMode = true)
        assertEquals("PHONE · MANUAL", CarWidgetReadings.sourceLabel(valid))
        val rejected = selector.select(null, phone(10_000L).copy(provider = "network"),
            10_000L, manualPhoneMode = true)
        assertEquals("PHONE · NO FIX", CarWidgetReadings.sourceLabel(rejected))
        assertNull(CarWidgetReadings.speedKmh(rejected, false))
    }

    @Test fun speedAndCourseNeverInventVehicleMovement() {
        val selector = CarGpsSourceSelector()
        val stopped = selector.select(car(10_000L, 0f),
            phone(10_000L), 10_000L)
        assertNull(CarWidgetReadings.courseDegrees(stopped))
        val moving = selector.select(car(10_100L, 18f),
            phone(10_100L), 10_100L)
        assertEquals(0f, CarWidgetReadings.courseDegrees(moving)!!, 0f)
    }

    @Test fun uiTickLagIsIndependentOfGpsSourceSwitchTimers() {
        val now = CarWidgetReadings.displayNow(10_000L,
            car(10_900L), phone(10_800L))
        assertEquals(10_900L, now)
    }

    @Test fun drMeasurementIsNotHeldBackByOneSecondUiRefreshTick() {
        val fresh = dr(10_080L)
        val now = CarWidgetReadings.displayNow(
            10_000L, car(10_000L), phone(10_000L), fresh)
        assertEquals(10_080L, now)
        val selected = CarGpsSourceSelector().select(
            car(10_000L), phone(10_000L), now, drFix = fresh)
        assertEquals(CarGpsSourceSelector.Source.DR, selected.source)
    }

    @Test fun noValidSourceCannotInventCoordinates() {
        val reading = CarGpsSourceSelector().select(null,
            phone(10_000L), 10_000L)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN, reading.source)
        assertEquals("NO VERIFIED POSITION", CarWidgetReadings.sourceLabel(reading))
        assertNull(reading.latitude)
        assertNull(CarWidgetReadings.speedKmh(reading, false))
    }
}
