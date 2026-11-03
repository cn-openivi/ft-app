package app.ft.ui.car

import app.ft.core.CarInfo
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

    private fun connectedSelector(): CarGpsSourceSelector {
        val selector = CarGpsSourceSelector()
        for (t in 10_000L..17_000L step 1_000L) {
            val selected = selector.select(car(t), phone(t), t)
            if (t < 17_000L) assertEquals(CarGpsSourceSelector.Source.PHONE, selected.source)
        }
        return selector
    }

    @Test fun carTakesPriorityOnlyAfterSevenSecondsOfContinuousGpsFrames() {
        val selector = CarGpsSourceSelector()
        var selection = selector.select(car(10_000L), phone(10_000L), 10_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, selection.source)
        assertEquals(55.55, selection.latitude!!, 0.0001)
        for (t in 11_000L..17_000L step 1_000L)
            selection = selector.select(car(t), phone(t), t)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, selection.source)
        assertEquals(55.55207, selection.latitude!!, 0.000001)
        assertEquals(231f, selection.headingDegrees!!, 0f)
    }

    @Test fun oneToSixSecondGpsOutageNeverChangesSourceOrErasesLastCarFix() {
        val selector = connectedSelector()
        for (gap in 1_000L..6_000L step 1_000L) {
            // The ICCOA service may clear VehicleTelemetry at disconnect.
            val selected = selector.select(null, phone(17_000L + gap),
                17_000L + gap)
            assertEquals(CarGpsSourceSelector.Source.ICCOA, selected.source)
            assertEquals(55.55207, selected.latitude!!, 0.000001)
            assertEquals(gap, selected.lastPacketAgeMs)
        }
    }

    @Test fun sevenSecondGpsOutageFallsBackToRealPhoneCoordinatesAndSpeed() {
        val selector = connectedSelector()
        val fallback = selector.select(null, phone(24_000L, 27f), 24_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, fallback.source)
        assertEquals(55.55, fallback.latitude!!, 0.00001)
        assertEquals(37.56, fallback.longitude!!, 0.00001)
        assertEquals(27f, fallback.speedKmh!!, 0f)
        assertEquals(220f, fallback.headingDegrees!!, 0f)
    }

    @Test fun sevenSecondStableReacquisitionReturnsToCarWithoutFlapping() {
        val selector = connectedSelector()
        selector.select(null, phone(24_000L), 24_000L)
        for (t in 25_000L..31_000L step 1_000L) {
            val result = selector.select(car(t), phone(t), t)
            assertEquals(CarGpsSourceSelector.Source.PHONE, result.source)
        }
        val result = selector.select(car(32_000L), phone(32_000L), 32_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, result.source)
        assertEquals(55.55207, result.latitude!!, 0.000001)
    }

    @Test fun gearPacketsDoNotResetGpsLossTimer() {
        val selector = connectedSelector()
        for (t in 18_000L..24_000L step 1_000L) {
            // GearInfo refreshes receivedAtMs but never positionAtMs.
            val gearOnly = car(17_000L).copy(receivedAtMs = t,
                gearSpeedRaw = 30L, gearAtMs = t)
            val selected = selector.select(gearOnly, phone(t), t)
            if (t < 24_000L)
                assertEquals(CarGpsSourceSelector.Source.ICCOA, selected.source)
            else assertEquals(CarGpsSourceSelector.Source.PHONE, selected.source)
        }
    }

    @Test fun interruptedReacquisitionResetsTheSevenSecondCounter() {
        val selector = CarGpsSourceSelector()
        for (t in 10_000L..12_000L step 1_000L)
            selector.select(car(t), phone(t), t)
        assertEquals(CarGpsSourceSelector.Source.PHONE,
            selector.select(car(17_000L), phone(17_000L), 17_000L).source)
        for (t in 18_000L..23_000L step 1_000L)
            assertEquals(CarGpsSourceSelector.Source.PHONE,
                selector.select(car(t), phone(t), t).source)
        assertEquals(CarGpsSourceSelector.Source.ICCOA,
            selector.select(car(24_000L), phone(24_000L), 24_000L).source)
    }

    @Test fun oldStoredCarFixCannotActivateCarAfterScreenReopen() {
        val selector = CarGpsSourceSelector()
        val result = selector.select(car(1_000L), phone(20_000L), 20_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, result.source)
        assertEquals(55.55, result.latitude!!, 0.000001)
    }

    @Test fun speedIsTakenFromSelectedSourceAndCarQuantizationIsSmoothed() {
        val selector = connectedSelector()
        val previous = selector.select(car(18_000L, 28.8f), phone(18_000L, 60f),18_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, previous.source)
        assertTrue(previous.speedKmh!! < 30f)
        val next = selector.select(car(19_000L, 32.4f),phone(19_000L,60f),19_000L)
        assertTrue(next.speedKmh!! > previous.speedKmh!!)
        assertTrue(next.speedKmh!! < 32.4f)
        // Phone readings must not override the selected OEM source.
        assertEquals(55.55207,next.latitude!!,0.00001)
    }

    @Test fun zeroSatelliteCountDoesNotDiscardNewValidOemPositionPackets() {
        val selector = CarGpsSourceSelector()
        for (t in 10_000L..17_000L step 1_000L) {
            val freshPosition = car(t).copy(satellitesUsed = 0L, satellitesVisible = 0L)
            val r = selector.select(freshPosition, phone(t, 95f), t)
            assertEquals(
                if (t < 17_000L) CarGpsSourceSelector.Source.PHONE
                else CarGpsSourceSelector.Source.ICCOA,
                r.source
            )
        }
        val result = selector.select(
            car(18_000L).copy(satellitesUsed = 0L), phone(18_000L, 95f), 18_000L
        )
        assertEquals(CarGpsSourceSelector.Source.ICCOA, result.source)
        assertEquals(55.55207, result.latitude!!, 0.00001)
        assertTrue(result.speedKmh!! < 95f)
    }

    @Test fun fallbackIsBasedOnGpsPositionTimestampNotFreshGearTraffic() {
        val selector = connectedSelector()
        for (t in 18_000L..24_000L step 1_000L) {
            val refresh = car(17_000L).copy(
                receivedAtMs = t, positionAtMs = 17_000L,
                gearAtMs = t, gearSpeedRaw = 28L
            )
            val result = selector.select(refresh, phone(t, 42f), t)
            assertEquals(
                if (t < 24_000L) CarGpsSourceSelector.Source.ICCOA
                else CarGpsSourceSelector.Source.PHONE,
                result.source
            )
        }
    }

    @Test fun invalidPhoneCoordinatesDoNotLeakIntoFallbackOrOverrideCar() {
        val selector = CarGpsSourceSelector()
        val invalidPhone = phone(10_000L).copy(latitude = 91.0)
        val cold = selector.select(null, invalidPhone, 10_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, cold.source)
        assertNull(cold.latitude)
        assertNull(cold.longitude)
        assertNull(cold.speedKmh)

        // A valid car stream is preferred even while phone GNSS is corrupt.
        for (t in 11_000L..18_000L step 1_000L)
            selector.select(car(t), invalidPhone, t)
        val carReading = selector.select(car(19_000L), invalidPhone, 19_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, carReading.source)
        assertEquals(55.55207, carReading.latitude!!, 0.00001)

        // After 7s without an OEM position, do not report invalid phone
        // coordinates or misleading speed as if they were a valid fallback.
        val fallback = selector.select(null, invalidPhone, 26_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, fallback.source)
        assertNull(fallback.latitude)
        assertNull(fallback.longitude)
        assertNull(fallback.speedKmh)
    }

    @Test fun invalidCarCoordinatesNeverBecomeCandidateFix() {
        val selector = CarGpsSourceSelector()
        for (t in 10_000L..30_000L step 1_000L) {
            val invalid = car(t).copy(latitude = 91.0)
            assertEquals(CarGpsSourceSelector.Source.PHONE,
                selector.select(invalid,phone(t),t).source)
        }
    }
}
