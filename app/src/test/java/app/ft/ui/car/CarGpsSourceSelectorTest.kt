package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.GpsSourceMode
import app.ft.core.VehicleLocation
import app.ft.dr.DrNavigationFix
import org.junit.Assert.*
import org.junit.Test

/** Owner regression: new OEM GNSS + continuously available verified DR, manual PHONE. */
class CarGpsSourceSelectorTest {
    private fun car(t: Long, latitude: Double = 55.7) =
        VehicleLocation(source = "ICCOA", receivedAtMs = t, positionAtMs = t,
            latitude = latitude, longitude = 37.6, speedKmh = 27f,
            headingDegrees = 90f, coordinatesUnit = "ANDROID_LOCATION_WGS84")

    private fun phone(t: Long, provider: String = "gps") = CarInfo.PhoneGpsFix(
        latitude = 55.6, longitude = 37.5, speedKmh = 28f,
        bearingDegrees = 80f, receivedAtMs = t,
        provider = provider, horizontalAccuracyMetres = 10f,
        fixAgeNanosAtReceipt = 0L)

    private fun verifiedDr(at: Long, trusted: Boolean = true,
        mode: GpsSourceMode = GpsSourceMode.CAR) = DrNavigationFix(
        latitude = 55.701, longitude = 37.601,
        accuracyMetres = 12.3, headingDegrees = 87.0,
        fromSelectedGps = mode, producedAtMs = at,
        estimated = true, verifiedVehicleMotion = trusted)

    @Test fun onlyManualModeSelectsPhoneEvenIfNoCarGpsExists() {
        val selector = CarGpsSourceSelector()
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN,
            selector.select(null, phone(10_000L), 10_000L).source)
        val manual = selector.select(null, phone(10_000L), 10_000L,
            manualPhoneMode = true)
        assertEquals(CarGpsSourceSelector.Source.PHONE, manual.source)
        assertEquals(55.6, manual.latitude!!, 0.0001)
    }

    @Test fun firstVerifiedCarFixAvailableWithoutThreeFixWait() {
        val reading = CarGpsSourceSelector().select(car(10_000L),
            phone(10_000L), 10_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, reading.source)
        assertEquals(55.7, reading.latitude!!, 0.0001)
        assertEquals(27f, reading.speedKmh!!, 0f)
    }

    @Test fun continuousDrDisplacesOemBeforeNextTenHzFrameNoLossTimeout() {
        val selector = CarGpsSourceSelector()
        val oem = car(10_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA,
            selector.select(oem, phone(10_000L), 10_000L).source)
        val next = selector.select(oem, phone(10_080L),
            10_080L, drFix = verifiedDr(10_080L))
        assertEquals(CarGpsSourceSelector.Source.DR, next.source)
        assertEquals(55.701, next.latitude!!, 0.0001)
        assertEquals(37.601, next.longitude!!, 0.0001)
        assertNull(next.speedKmh)
    }

    @Test fun unverifiedPhoneDrOrOldDrNeverReplacesAuthenticOem() {
        val oem = car(10_000L)
        val cases = listOf(verifiedDr(10_080L, false),
            verifiedDr(10_080L, mode = GpsSourceMode.PHONE),
            verifiedDr(8_000L))
        for (dr in cases) {
            assertEquals(CarGpsSourceSelector.Source.ICCOA,
                CarGpsSourceSelector().select(oem, phone(10_080L),
                    10_080L, drFix = dr).source)
        }
    }

    @Test fun gearInfoCannotRefreshOemGpsOrTriggerPhoneFallback() {
        val oem = car(10_000L)
        val gearOnly = oem.copy(receivedAtMs = 90_000L,
            gearAtMs = 90_000L, gearSpeedAtMs = 90_000L,
            gearRaw = 2L, gearSpeedRaw = 20L)
        val reading = CarGpsSourceSelector().select(gearOnly,
            phone(90_000L), 90_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, reading.source)
        assertEquals(10_000L, reading.car!!.positionAtMs)
        assertEquals(80_000L, reading.lastPacketAgeMs)
        assertNull(reading.phone)
    }

    @Test fun noSourceIsInventedFromMissingOrInvalidData() {
        val selector = CarGpsSourceSelector()
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN,
            selector.select(null, phone(10_000L), 10_000L).source)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN,
            selector.select(car(10_000L, 0.0).copy(longitude = 0.0),
                phone(10_000L), 10_000L).source)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN,
            selector.select(car(10_000L, 100.0), phone(10_000L), 10_000L).source)
    }

    @Test fun networkLocationNeverMasqueradesAsManualSatelliteFix() {
        val selector = CarGpsSourceSelector()
        val reading = selector.select(null, phone(10_000L, "network"),
            10_000L, manualPhoneMode = true)
        assertEquals(CarGpsSourceSelector.Source.PHONE, reading.source)
        assertNull(reading.latitude)
        assertNull(reading.longitude)
        val futureCallback = selector.select(null,
            phone(11_000L), 10_000L, manualPhoneMode = true)
        assertNull(futureCallback.latitude)
        val negativeFixAge = selector.select(null,
            phone(10_000L).copy(fixAgeNanosAtReceipt = -1L),
            10_000L, manualPhoneMode = true)
        assertNull(negativeFixAge.latitude)
    }


}
