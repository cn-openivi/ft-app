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
            speedKmh = speed, headingDegrees = 0f)
    private fun dr(t: Long) = DrNavigationFix(55.55, 37.56, 14.5, 120.0,
        GpsSourceMode.CAR, t, true, true)

    @Test fun carCoordinatesAreDefaultAndNeverReplacedByPhoneAutomatically() {
        val selector = CarGpsSourceSelector()
        val carReading = selector.select(car(10_000L), phone(10_000L), 10_000L)
        assertEquals("ICCOA", CarWidgetReadings.sourceLabel(carReading))
        assertEquals(25.2f, CarWidgetReadings.speedKmh(carReading, false)!!, 0f)
        val lost = selector.select(car(10_000L), phone(16_000L), 16_000L)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN, lost.source)
        assertEquals("GPS LOST", CarWidgetReadings.sourceLabel(lost))
        assertNull(CarWidgetReadings.speedKmh(lost, false))
    }

    @Test fun drIsLabeledEstimatedAndDoesNotInventSpeed() {
        val reading = CarGpsSourceSelector().select(
            car(10_000L), phone(16_000L), 16_000L, drFix = dr(16_000L))
        assertEquals(CarGpsSourceSelector.Source.DR, reading.source)
        assertEquals("DR estimated ±15m", CarWidgetReadings.sourceLabel(reading))
        assertNull(CarWidgetReadings.speedKmh(reading, false))
    }

    @Test fun shortGpsPauseDoesNotSwitchSourceOrUseStalePhone() {
        val reading = CarGpsSourceSelector().select(
            car(10_000L), phone(13_000L), 13_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, reading.source)
        assertEquals("ICCOA · HOLD 3s", CarWidgetReadings.sourceLabel(reading))
        assertEquals(25.2f, CarWidgetReadings.speedKmh(reading, false)!!, 0f)
    }

    @Test fun manualPhoneIsLabeledAndUsedOnlyWhenRequested() {
        val reading = CarGpsSourceSelector().select(
            null, phone(10_000L), 10_000L, manualPhoneMode = true)
        assertEquals("PHONE · MANUAL", CarWidgetReadings.sourceLabel(reading))
        assertEquals(26f, CarWidgetReadings.speedKmh(reading, false)!!, 0f)
        val bad = CarGpsSourceSelector().select(
            null, phone(10_000L).copy(provider = "network"),
            10_000L, manualPhoneMode = true)
        assertEquals("PHONE · NO FIX", CarWidgetReadings.sourceLabel(bad))
        assertNull(CarWidgetReadings.speedKmh(bad, false))
    }

    @Test fun uiTickLagCannotMakeIncomingFixLookOld() {
        val current = CarWidgetReadings.displayNow(10_000L,
            car(10_900L), phone(10_800L))
        assertEquals(10_900L, current)
    }

    @Test fun invalidPhoneSpeedIsNeverDisplayedAsRealMeasurement() {
        val reading = CarGpsSourceSelector().select(
            null, phone(10_000L, Float.NaN), 10_000L,
            manualPhoneMode = true)
        assertNull(CarWidgetReadings.speedKmh(reading, false))
        assertEquals(60f, CarWidgetReadings.speedKmh(reading, true)!!, 0f)
    }

    @Test fun stoppedOemZeroCourseNotMisreportedAsNorth() {
        val reading = CarGpsSourceSelector().select(
            car(10_000L, 0f), phone(10_000L), 10_000L)
        assertEquals(0f, reading.headingDegrees!!, 0f)
        assertNull(CarWidgetReadings.courseDegrees(reading))
        val moving = CarGpsSourceSelector().select(
            car(10_000L, 18f), phone(10_000L), 10_000L)
        assertEquals(0f, CarWidgetReadings.courseDegrees(moving)!!, 0f)
    }

    @Test fun manualPhoneCourseAlsoRequiresMovement() {
        val selector = CarGpsSourceSelector()
        val stopped = selector.select(null,
            phone(10_000L, 0f), 10_000L, manualPhoneMode = true)
        assertNull(CarWidgetReadings.courseDegrees(stopped))
        val moving = selector.select(null,
            phone(10_000L, 22f).copy(bearingDegrees = 0f), 10_000L,
            manualPhoneMode = true)
        assertEquals(0f, CarWidgetReadings.courseDegrees(moving)!!, 0f)
    }

    @Test fun unknownPositionDoesNotInventLocationOrSpeed() {
        val reading = CarGpsSourceSelector().select(null, phone(10_000L), 10_000L)
        assertNull(reading.latitude)
        assertNull(reading.longitude)
        assertNull(CarWidgetReadings.speedKmh(reading, false))
    }
}
