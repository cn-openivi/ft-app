package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.GpsSourceMode
import app.ft.core.VehicleLocation
import org.junit.Assert.*
import org.junit.Test

class CarGpsSourceSelectorTest {
    private fun car(t: Long, speed: Float = 28.8f) = VehicleLocation(
        source = "ICCOA", receivedAtMs = t, positionAtMs = t,
        latitude = 55.55207, longitude = 37.55462,
        speedKmh = speed, headingDegrees = 231f,
        coordinatesUnit = "ANDROID_LOCATION_WGS84"
    )
    private fun phone(t: Long, speed: Float = 26f) = CarInfo.PhoneGpsFix(
        latitude = 55.55, longitude = 37.56, speedKmh = speed,
        bearingDegrees = 220f, receivedAtMs = t
    )

    @Test fun carModeSelectsCarImmediatelyWithoutSevenSecondWait() {
        val selector = CarGpsSourceSelector()
        val r = selector.select(car(10_000L), phone(10_000L), 10_000L, GpsSourceMode.CAR)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, r.source)
        assertEquals(55.55207, r.latitude!!, 0.00001)
        assertEquals(231f, r.headingDegrees!!, 0f)
    }

    @Test fun carModeNeverFallsBackToPhoneAfterLongTunnel() {
        val selector = CarGpsSourceSelector()
        selector.select(car(10_000L), phone(10_000L), 10_000L, GpsSourceMode.CAR)
        for (gap in listOf(1_000L, 7_000L, 60_000L, 600_000L)) {
            val r = selector.select(null, phone(10_000L + gap, 95f),
                10_000L + gap, GpsSourceMode.CAR)
            assertEquals(CarGpsSourceSelector.Source.ICCOA, r.source)
            assertEquals(55.55207, r.latitude!!, 0.00001)
            assertEquals(gap, r.lastPacketAgeMs)
            assertTrue(r.speedKmh!! < 95f)
        }
    }

    @Test fun withoutCarFixCarModeShowsMissingNotPhoneCoordinates() {
        val selector = CarGpsSourceSelector()
        val r = selector.select(null, phone(21_000L), 21_000L, GpsSourceMode.CAR)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, r.source)
        assertNull(r.latitude)
        assertNull(r.longitude)
        assertNull(r.speedKmh)
    }

    @Test fun phoneModeNeverBorrowsCarFixEvenAfterPhoneDisappears() {
        val selector = CarGpsSourceSelector()
        val first = selector.select(car(10_000L), phone(10_000L), 10_000L,
            GpsSourceMode.PHONE)
        assertEquals(CarGpsSourceSelector.Source.PHONE, first.source)
        assertEquals(55.55, first.latitude!!, 0.00001)
        val lost = selector.select(car(50_000L), null, 50_000L, GpsSourceMode.PHONE)
        assertEquals(CarGpsSourceSelector.Source.PHONE, lost.source)
        assertNull(lost.latitude)
        assertNull(lost.longitude)
        assertNull(lost.speedKmh)
    }

    @Test fun switchingSettingTakesEffectImmediatelyWithoutHysteresis() {
        val selector = CarGpsSourceSelector()
        val phone = selector.select(car(10_000L), phone(10_000L), 10_000L,
            GpsSourceMode.PHONE)
        assertEquals(CarGpsSourceSelector.Source.PHONE, phone.source)
        val vehicle = selector.select(car(10_100L), phone(10_100L), 10_100L,
            GpsSourceMode.CAR)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, vehicle.source)
        assertEquals(55.55207, vehicle.latitude!!, 0.00001)
        val back = selector.select(car(10_200L), phone(10_200L), 10_200L,
            GpsSourceMode.PHONE)
        assertEquals(CarGpsSourceSelector.Source.PHONE, back.source)
        assertEquals(55.55, back.latitude!!, 0.00001)
    }

    @Test fun carGearOnlyDoesNotPretendToBeNewGpsPosition() {
        val selector = CarGpsSourceSelector()
        val first = car(10_000L)
        selector.select(first, phone(10_000L), 10_000L, GpsSourceMode.CAR)
        for (now in 11_000L..30_000L step 1_000L) {
            val gearOnly = first.copy(receivedAtMs = now, gearAtMs = now,
                gearSpeedRaw = 22L)
            val r = selector.select(gearOnly, phone(now), now, GpsSourceMode.CAR)
            assertEquals(CarGpsSourceSelector.Source.ICCOA, r.source)
            assertEquals(now - 10_000L, r.lastPacketAgeMs)
            assertEquals(55.55207, r.latitude!!, 0.00001)
        }
    }

    @Test fun freshZeroSatelliteOemPositionRemainsValidInCarMode() {
        val selector = CarGpsSourceSelector()
        for (now in 10_000L..30_000L step 1_000L) {
            val r = selector.select(car(now).copy(
                satellitesUsed = 0L, satellitesVisible = 0L
            ), phone(now, 90f), now, GpsSourceMode.CAR)
            assertEquals(CarGpsSourceSelector.Source.ICCOA, r.source)
            assertEquals(55.55207, r.latitude!!, 0.00001)
        }
    }

    @Test fun oldCachedCarFixIsNotAcceptedAsFreshWhenScreenReopens() {
        val selector = CarGpsSourceSelector()
        val r = selector.select(car(1_000L), phone(20_000L), 20_000L,
            GpsSourceMode.CAR)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, r.source)
        assertNull(r.latitude)
    }

    @Test fun invalidCarAndPhoneCoordinatesNeverCrossSourceBoundary() {
        val selector = CarGpsSourceSelector()
        assertNull(selector.select(car(10_000L).copy(latitude = 91.0),
            phone(10_000L), 10_000L, GpsSourceMode.CAR).latitude)
        assertNull(selector.select(car(11_000L),
            phone(11_000L).copy(latitude = 91.0), 11_000L,
            GpsSourceMode.PHONE).latitude)
    }

    @Test fun speedIsSmoothedFromCarOnly() {
        val selector = CarGpsSourceSelector()
        val first = selector.select(car(10_000L, 28.8f), phone(10_000L, 60f),
            10_000L, GpsSourceMode.CAR)
        val next = selector.select(car(11_000L, 32.4f), phone(11_000L, 60f),
            11_000L, GpsSourceMode.CAR)
        assertEquals(28.8f, first.speedKmh!!, 0f)
        assertTrue(next.speedKmh!! > first.speedKmh!!)
        assertTrue(next.speedKmh!! < 32.4f)
    }

    @Test fun carCoordinatesNotYetVerifiedForBaiduNeverFallbackSilently() {
        val selector = CarGpsSourceSelector()
        val unverified = VehicleLocation("BAIDU", 11_000L,
            latitude = 55.55, longitude = 37.56)
        val selected = selector.select(unverified, phone(11_000L),
            11_000L, GpsSourceMode.CAR)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, selected.source)
        assertNull(selected.latitude)
    }
}
