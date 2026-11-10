package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.GpsSourceMode
import app.ft.core.VehicleLocation
import app.ft.dr.DrNavigationFix
import org.junit.Assert.*
import org.junit.Test

/** Regression for owner CAR -> verified DR -> UNKNOWN, manual PHONE only. */
class CarGpsSourceSelectorTest {
    private fun car(t: Long, lat: Double = 55.7): VehicleLocation =
        VehicleLocation("ICCOA", receivedAtMs = t, positionAtMs = t,
            latitude = lat, longitude = 37.6, speedKmh = 27f,
            headingDegrees = 90f, coordinatesUnit = "ANDROID_LOCATION_WGS84")

    private fun phone(t: Long, provider: String = "gps"): CarInfo.PhoneGpsFix =
        CarInfo.PhoneGpsFix(latitude = 55.6, longitude = 37.5,
            speedKmh = 28f, bearingDegrees = 80f, receivedAtMs = t,
            provider = provider, horizontalAccuracyMetres = 10f,
            fixAgeNanosAtReceipt = 0L)

    private fun verifiedDr(t: Long, trusted: Boolean = true,
        mode: GpsSourceMode = GpsSourceMode.CAR
    ): DrNavigationFix = DrNavigationFix(
        latitude = 55.701, longitude = 37.601,
        accuracyMetres = 12.3, headingDegrees = 87.0,
        fromSelectedGps = mode, producedAtMs = t, estimated = true,
        verifiedVehicleMotion = trusted
    )

    @Test fun phoneIsNeverAutomaticallySelectedEvenAtStart() {
        val selected = CarGpsSourceSelector().select(null, phone(10_000L), 10_000L)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN, selected.source)
        assertNull(selected.latitude)
        assertNull(selected.longitude)
    }

    @Test fun firstNewCarFixIsAvailableImmediatelyWithoutThreeFixWait() {
        val selected = CarGpsSourceSelector().select(car(10_000L), phone(10_000L), 10_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, selected.source)
        assertEquals(55.7, selected.latitude!!, 0.0001)
        assertEquals(27f, selected.speedKmh!!, 0f)
    }

    @Test fun noThreeSecondGpsFilterAndLossExactlyAtFiveSeconds() {
        val selector = CarGpsSourceSelector()
        val fix = car(10_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA,
            selector.select(fix, phone(14_900L), 14_900L).source)
        val lost = selector.select(fix, phone(15_000L), 15_000L)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN, lost.source)
        assertNull(lost.latitude)
        assertNull(lost.speedKmh)
        assertEquals(5_000L, lost.lastPacketAgeMs)
    }

    @Test fun staleCarFixAndNewGearInfoNeverTriggerPhoneFallback() {
        val selector = CarGpsSourceSelector()
        val old = car(10_000L)
        val gearOnly = old.copy(receivedAtMs = 90_000L,
            gearAtMs = 90_000L, gearSpeedAtMs = 90_000L,
            gearRaw = 2L, gearSpeedRaw = 20L)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN,
            selector.select(gearOnly, phone(90_000L), 90_000L).source)
        assertNull(selector.select(gearOnly, phone(90_100L), 90_100L).latitude)
    }

    @Test fun lossUsesOnlyGenuinelyVerifiedCarDr() {
        val selector = CarGpsSourceSelector()
        val reading = selector.select(car(10_000L), phone(16_000L),
            16_000L, drFix = verifiedDr(16_000L))
        assertEquals(CarGpsSourceSelector.Source.DR, reading.source)
        assertEquals(55.701, reading.latitude!!, 0.0001)
        assertEquals(37.601, reading.longitude!!, 0.0001)
        assertNull(reading.speedKmh) // No invented wheel/GNSS speed from DR.
        assertEquals(12.3, reading.dr!!.accuracyMetres, 0.001)
    }

    @Test fun invalidUnverifiedOrOldDrMustNotInventLocation() {
        val selector = CarGpsSourceSelector()
        val old = car(10_000L)
        val now = 20_000L
        val cases = listOf(
            verifiedDr(now, trusted = false),
            verifiedDr(now, mode = GpsSourceMode.PHONE),
            verifiedDr(now - 1_501L)
        )
        for (dr in cases) {
            val reading = selector.select(old, phone(now), now, drFix = dr)
            assertEquals(CarGpsSourceSelector.Source.UNKNOWN, reading.source)
            assertNull(reading.latitude)
        }
    }

    @Test fun accurateGnssReturnImmediatelyReplacesDrWithoutPhone() {
        val selector = CarGpsSourceSelector()
        selector.select(car(10_000L), phone(16_000L), 16_000L,
            drFix = verifiedDr(16_000L))
        val recovered = selector.select(car(16_100L, 55.702), phone(16_100L),
            16_100L, drFix = verifiedDr(16_100L))
        assertEquals(CarGpsSourceSelector.Source.ICCOA, recovered.source)
        assertEquals(55.702, recovered.latitude!!, 0.00001)
    }

    @Test fun manualPhoneSelectionIsExplicitAndNeverTriggeredByLoss() {
        val selector = CarGpsSourceSelector()
        val auto = selector.select(null, phone(10_000L), 10_000L)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN, auto.source)
        val manual = selector.select(null, phone(10_000L), 10_000L,
            manualPhoneMode = true)
        assertEquals(CarGpsSourceSelector.Source.PHONE, manual.source)
        assertEquals(55.6, manual.latitude!!, 0.0001)
        assertEquals(28f, manual.speedKmh!!, 0f)
    }

    @Test fun manualPhoneNetworkLocationAndStaleFixAreNotPassedAsCarGnss() {
        val selector = CarGpsSourceSelector()
        val network = selector.select(null, phone(10_000L, "network"), 10_000L,
            manualPhoneMode = true)
        assertEquals(CarGpsSourceSelector.Source.PHONE, network.source)
        assertNull(network.latitude)
        val stale = selector.select(null, phone(10_000L), 16_000L,
            manualPhoneMode = true)
        assertNull(stale.latitude)
        val staleOriginal = selector.select(null,
            phone(10_000L).copy(fixAgeNanosAtReceipt = 5_000_000_001L),
            10_000L, manualPhoneMode = true)
        assertNull(staleOriginal.latitude)
    }

    @Test fun physicallyImpossibleOemCoordinatesNeverLookLive() {
        val selector = CarGpsSourceSelector()
        val zero = selector.select(car(10_000L, 0.0).copy(longitude = 0.0),
            phone(10_000L), 10_000L)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN, zero.source)
        val outOfRange = selector.select(car(10_100L, 100.0),
            phone(10_100L), 10_100L)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN, outOfRange.source)
    }

    @Test fun transitionsAreReportedWithoutInventingPhoneSelection() {
        val transitions = mutableListOf<Pair<CarGpsSourceSelector.Source,
            CarGpsSourceSelector.Source>>()
        val selector = CarGpsSourceSelector(onTransition = { from, to, _ ->
            transitions += from to to
        })
        selector.select(car(10_000L), phone(10_000L), 10_000L)
        selector.select(car(10_000L), phone(16_000L), 16_000L)
        selector.select(car(10_000L), phone(16_100L), 16_100L,
            drFix = verifiedDr(16_100L))
        assertEquals(listOf(
            CarGpsSourceSelector.Source.UNKNOWN to CarGpsSourceSelector.Source.ICCOA,
            CarGpsSourceSelector.Source.ICCOA to CarGpsSourceSelector.Source.UNKNOWN,
            CarGpsSourceSelector.Source.UNKNOWN to CarGpsSourceSelector.Source.DR
        ), transitions)
    }
}
