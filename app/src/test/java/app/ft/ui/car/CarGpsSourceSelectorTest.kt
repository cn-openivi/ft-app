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

    @Test fun handsetGpsWithPoorAccuracyOrUnexpectedProviderIsHiddenInGarage() {
        val selector = CarGpsSourceSelector()
        val uncertain = phone(10_000L).copy(provider = "gps", horizontalAccuracyMetres = 350f)
        val result = selector.select(null, uncertain, 10_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, result.source)
        assertNull(result.latitude)
        assertNull(result.speedKmh)
        val network = phone(10_100L).copy(provider = "network", horizontalAccuracyMetres = 8f)
        assertNull(selector.select(null, network, 10_100L).latitude)
        val accurate = phone(11_000L).copy(provider = "gps", horizontalAccuracyMetres = 15f)
        assertEquals(55.55, selector.select(null, accurate, 11_000L).latitude!!, 0.000001)
    }

    @Test fun sourceTransitionsAreLoggedOnceWithoutGearRefreshFlapping() {
        val transitions = mutableListOf<String>()
        val selector = CarGpsSourceSelector(onTransition = { old, new, _ ->
            transitions.add("$old->$new")
        })
        for (t in 10_000L..17_000L step 1_000L) selector.select(car(t), phone(t), t)
        for (t in 18_000L..24_000L step 1_000L)
            selector.select(car(17_000L).copy(receivedAtMs = t), phone(t), t)
        assertEquals(listOf("PHONE->ICCOA", "ICCOA->PHONE"), transitions)
    }

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

    @Test fun stalePhoneCallbackCannotBecomeARealBackupAtSevenSecondHandoff() {
        val selector = connectedSelector()
        val staleHandset = phone(10_000L, speed = 83f)
        val fallback = selector.select(null, staleHandset, 24_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, fallback.source)
        assertNull(fallback.latitude)
        assertNull(fallback.longitude)
        assertNull(fallback.speedKmh)
        val recoveredPhone = selector.select(null, phone(24_500L, speed = 31f), 24_500L)
        assertEquals(55.55, recoveredPhone.latitude!!, 0.00001)
        assertEquals(31f, recoveredPhone.speedKmh!!, 0f)
    }

    @Test fun recentlyReceivedButOldGpsProviderFixIsNotFreshPhoneFallback() {
        val selector = connectedSelector()
        // LocationManager may replay a cached GNSS fix in a fresh callback.
        val cachedLocation = phone(24_000L, speed = 74f)
            .copy(fixAgeNanosAtReceipt = 12_000_000_000L)
        val fallback = selector.select(null, cachedLocation, 24_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, fallback.source)
        assertNull(fallback.latitude)
        assertNull(fallback.speedKmh)
        val currentFix = phone(24_100L, speed = 33f)
            .copy(fixAgeNanosAtReceipt = 1_000_000_000L)
        val current = selector.select(null, currentFix, 24_100L)
        assertEquals(55.55, current.latitude!!, 0.00001)
        assertEquals(33f, current.speedKmh!!, 0f)
    }


    @Test fun phoneReadingAgeIncludesOriginalGnssFixAge() {
        val selector = CarGpsSourceSelector()
        val fix = phone(18_000L).copy(fixAgeNanosAtReceipt = 1_000_000_001L)
        val result = selector.select(null, fix, 19_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, result.source)
        assertEquals(2_001L, result.lastPacketAgeMs)
        assertEquals(55.55, result.latitude!!, 0.00001)
        // No elapsedRealtime metadata: preserve callback-age compatibility.
        val legacy = selector.select(null, phone(18_000L), 19_000L)
        assertEquals(1_000L, legacy.lastPacketAgeMs)
    }

    @Test fun phoneFallbackChecksTotalGnssMeasurementAgeAcrossCallbackDelay() {
        val selector = connectedSelector() // Last accepted OEM position at 17_000 ms.
        val twelveSecondOld = phone(18_000L, speed = 80f)
            .copy(fixAgeNanosAtReceipt = 6_000_000_000L)
        // At 24_000 ms the callback is 6s old. Each age separately is <7s,
        // but the actual GPS measurement is now 12s old.
        val rejected = selector.select(null, twelveSecondOld, 24_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, rejected.source)
        assertNull(rejected.latitude)
        assertNull(rejected.longitude)
        assertNull(rejected.speedKmh)

        // An exactly 7s-old measurement is still permitted by the owner
        // 7s fallback budget; an extra nanosecond must be rejected.
        val sevenSecondOld = phone(18_000L, speed = 33f)
            .copy(fixAgeNanosAtReceipt = 1_000_000_000L)
        val boundary = selector.select(null, sevenSecondOld, 24_000L)
        assertEquals(55.55, boundary.latitude!!, 0.00001)
        assertEquals(33f, boundary.speedKmh!!, 0f)

        val overBoundary = phone(18_000L, speed = 77f)
            .copy(fixAgeNanosAtReceipt = 1_000_000_001L)
        val expired = selector.select(null, overBoundary, 24_000L)
        assertNull(expired.latitude)
        assertNull(expired.speedKmh)

        // A future-dated Android elapsedRealtime fix is invalid, not fresh.
        val impossibleFutureFix = phone(24_000L, speed = 99f)
            .copy(fixAgeNanosAtReceipt = -1L)
        val invalid = selector.select(null, impossibleFutureFix, 24_000L)
        assertNull(invalid.latitude)
        assertNull(invalid.speedKmh)
    }

    @Test fun reorderedOemPacketsNeverRefreshSevenSecondCarLossTimer() {
        val selector = connectedSelector() // Accepted positions 10_000..17_000.
        // Exact reordering pattern: accepted T, then T-1000, then T-500.
        // Neither old packet is a new OEM GPS fix, even if the second
        // packet is newer than the first rejected packet.
        val firstOld = selector.select(car(16_000L), phone(18_000L), 18_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, firstOld.source)
        assertEquals(1_000L, firstOld.lastPacketAgeMs)

        val secondOld = selector.select(car(16_500L), phone(19_000L), 19_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, secondOld.source)
        assertEquals(2_000L, secondOld.lastPacketAgeMs)

        val beforeTimeout = selector.select(null, phone(23_999L), 23_999L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, beforeTimeout.source)
        assertEquals(6_999L, beforeTimeout.lastPacketAgeMs)

        val fallback = selector.select(null, phone(24_000L), 24_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, fallback.source)
        assertEquals(55.55, fallback.latitude!!, 0.00001)

        // Only genuinely newer positions can start a new 7s recovery.
        for (t in 25_000L..31_000L step 1_000L)
            assertEquals(CarGpsSourceSelector.Source.PHONE,
                selector.select(car(t), phone(t), t).source)
        assertEquals(CarGpsSourceSelector.Source.ICCOA,
            selector.select(car(32_000L), phone(32_000L), 32_000L).source)
    }

    @Test fun rejectedFutureOemPacketDoesNotPoisonCarPositionHighWaterMark() {
        val selector = connectedSelector() // Accepted OEM T=17_000.
        // This packet is 1001 ms in the future, beyond the tolerated
        // clock skew. It must NOT advance the accepted position watermark.
        val future = selector.select(car(21_001L), phone(20_000L), 20_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, future.source)
        assertEquals(3_000L, future.lastPacketAgeMs)

        // A real, fresh fix at 20_000 must still be accepted even though
        // its timestamp is below the previously rejected 21_001.
        val recovered = selector.select(car(20_000L), phone(20_000L), 20_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, recovered.source)
        assertEquals(0L, recovered.lastPacketAgeMs)

        // No further OEM position: seven seconds after the last accepted
        // fix the selector must return to PHONE, never prolonging a gap
        // because of a rejected future timestamp.
        val before = selector.select(null, phone(26_999L), 26_999L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, before.source)
        assertEquals(6_999L, before.lastPacketAgeMs)
        val fallback = selector.select(null, phone(27_000L), 27_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, fallback.source)
        assertEquals(55.55, fallback.latitude!!, 0.00001)
    }


    @Test fun firstOemFixAtSevenSecondBoundaryDoesNotHideCompletedGpsOutage() {
        val selector = connectedSelector() // ICCOA active; last OEM fix at 17_000.
        // Exactly 7s elapsed with no new OEM position. A fresh position
        // arrives in the same select() call that detects the expired gap.
        // It must start a NEW 7s reacquisition, not conceal the outage.
        val boundary = selector.select(car(24_000L), phone(24_000L), 24_000L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, boundary.source)
        assertEquals(55.55, boundary.latitude!!, 0.00001)
        assertEquals(0L, boundary.lastPacketAgeMs)

        // Reacquisition must be continuous and last a full seven seconds.
        for (t in 25_000L..30_000L step 1_000L) {
            val pending = selector.select(car(t), phone(t), t)
            assertEquals(CarGpsSourceSelector.Source.PHONE, pending.source)
        }
        val reacquired = selector.select(car(31_000L), phone(31_000L), 31_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, reacquired.source)
        assertEquals(55.55207, reacquired.latitude!!, 0.00001)
    }

    @Test fun sixSecondOemGapWithFreshFixRetainsCarSource() {
        val selector = connectedSelector()
        val recovered = selector.select(car(23_000L), phone(23_000L), 23_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, recovered.source)
        assertEquals(0L, recovered.lastPacketAgeMs)
    }

    @Test fun newCarPositionWithoutSpeedDoesNotReuseOldSpeed() {
        val selector = connectedSelector()
        val valid = selector.select(car(18_000L, speed = 54f), phone(18_000L), 18_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, valid.source)
        assertNotNull(valid.speedKmh)

        // A fresh OEM position with no measured speed is still a valid
        // CAR position, but an earlier speed is not a current measurement.
        val absent = selector.select(car(19_000L).copy(speedKmh = null),
            phone(19_000L, speed = 80f), 19_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, absent.source)
        assertEquals(55.55207, absent.latitude!!, 0.00001)
        assertNull(absent.speedKmh)

        // Invalid speed is likewise not evidence of current car speed.
        val invalid = selector.select(car(20_000L, speed = Float.NaN),
            phone(20_000L, speed = 90f), 20_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, invalid.source)
        assertNull(invalid.speedKmh)

        // The next valid speed resumes from the actual new measurement,
        // not an EMA that includes the stale 54 km/h value.
        val restored = selector.select(car(21_000L, speed = 20f),
            phone(21_000L, speed = 90f), 21_000L)
        assertEquals(20f, restored.speedKmh!!, 0f)
    }

    @Test fun invalidHeadingNeverLeaksFromSelectedGpsSource() {
        val selector = connectedSelector()
        val invalidCar = selector.select(
            car(18_000L).copy(headingDegrees = Float.NaN),
            phone(18_000L), 18_000L
        )
        assertEquals(CarGpsSourceSelector.Source.ICCOA, invalidCar.source)
        assertEquals(55.55207, invalidCar.latitude!!, 0.00001)
        assertNull(invalidCar.headingDegrees)

        val fallback = selector.select(
            null, phone(25_000L).copy(bearingDegrees = Float.POSITIVE_INFINITY), 25_000L
        )
        assertEquals(CarGpsSourceSelector.Source.PHONE, fallback.source)
        assertEquals(55.55, fallback.latitude!!, 0.00001)
        assertNull(fallback.headingDegrees)

        val outOfRange = selector.select(
            null, phone(25_100L).copy(bearingDegrees = -12f), 25_100L
        )
        assertNull(outOfRange.headingDegrees)
        val valid = selector.select(
            null, phone(25_200L).copy(bearingDegrees = 359.5f), 25_200L
        )
        assertEquals(359.5f, valid.headingDegrees!!, 0f)
    }

    @Test fun invalidCarCoordinatesNeverBecomeCandidateFix() {
        val selector = CarGpsSourceSelector()
        for (t in 10_000L..30_000L step 1_000L) {
            val invalid = car(t).copy(latitude = 91.0)
            assertEquals(CarGpsSourceSelector.Source.PHONE,
                selector.select(invalid,phone(t),t).source)
        }
    }
    @Test fun duplicateAtSevenSecondRecoveryBoundaryCannotActivateCar() {
        val selector = CarGpsSourceSelector()
        for (t in 10_000L..16_000L step 1_000L)
            assertEquals(CarGpsSourceSelector.Source.PHONE,
                selector.select(car(t), phone(t), t).source)
        // At the threshold there is no new OEM measurement, only GearInfo.
        val duplicate = selector.select(
            car(16_000L).copy(receivedAtMs = 17_000L, gearAtMs = 17_000L),
            phone(17_000L), 17_000L
        )
        assertEquals(CarGpsSourceSelector.Source.PHONE, duplicate.source)
        val fresh = selector.select(car(17_500L), phone(17_500L), 17_500L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, fresh.source)
    }

    @Test fun delayedNewOemPositionDoesNotExtendCarHysteresisPastMeasurementAge() {
        val selector = connectedSelector() // Last measurement at 17_000 ms.
        // Packet arrives at 20_000 but OEM position was measured at 17_500.
        val delayed = car(17_500L).copy(receivedAtMs = 20_000L)
        val accepted = selector.select(delayed, phone(20_000L), 20_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, accepted.source)
        assertEquals(2_500L, accepted.lastPacketAgeMs)

        val before = selector.select(null, phone(24_499L), 24_499L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, before.source)
        assertEquals(6_999L, before.lastPacketAgeMs)

        val expired = selector.select(null, phone(24_500L), 24_500L)
        assertEquals(CarGpsSourceSelector.Source.PHONE, expired.source)
        assertEquals(55.55, expired.latitude!!, 0.00001)
    }

}
