package app.ft.dr

import org.junit.Assert.*
import app.ft.core.GpsSourceMode
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class DeadReckoningEkfTest {
    @Test fun noEstimatedLocationBeforeTrueAnchorAndVerifiedOemSensors() {
        val dr = DeadReckoningEkf()
        assertNull(dr.estimate())
        assertNull(dr.predict(1.0, 10.0, 0.0, wheelAndGyroVerified = true))
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 3.0))
        assertNull(dr.predict(1.0, 10.0, 0.0, wheelAndGyroVerified = false))
        assertFalse(dr.estimate()!!.estimated)
        assertEquals(0.0, dr.estimate()!!.northMetres, 0.0)
    }

    @Test fun northboundCarMovesByCalibratedWheelDistanceOnly() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(100.0, 200.0, 0.0, 3.0))
        repeat(10) {
            val prediction = dr.predict(1.0, 10.0, 0.0, wheelAndGyroVerified = true)
            assertNotNull(prediction)
            assertTrue(prediction!!.estimated)
        }
        val position = dr.estimate()!!
        assertEquals(100.0, position.eastMetres, 1e-8)
        assertEquals(300.0, position.northMetres, 1e-8)
        assertEquals(0.0, position.headingDegrees, 1e-8)
        assertEquals(10.0, position.secondsSinceGnss, 1e-10)
    }

    @Test fun ninetyDegreeTurnUsesYawAndMidpointIntegration() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 0.0, 1.0)
        val prediction = dr.predict(
            dtSeconds = 1.0,
            calibratedSpeedMetresPerSecond = 10.0,
            vehicleYawRateRadiansPerSecond = PI / 2.0,
            wheelAndGyroVerified = true
        )!!
        assertEquals(90.0, prediction.headingDegrees, 1e-8)
        assertEquals(7.071067811865475, prediction.eastMetres, 1e-8)
        assertEquals(7.071067811865475, prediction.northMetres, 1e-8)
    }

    @Test fun trustedGnssCorrectionShrinksEstimateErrorWithoutTeleporting() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 0.0, 3.0)
        dr.predict(1.0, 10.0, 0.0, true)
        val before = dr.estimate()!!
        assertTrue(dr.correctGnss(0.5, 10.5, 1.0, genuineGnss = true))
        val after = dr.estimate()!!
        assertFalse(after.estimated)
        assertEquals(0.0, after.secondsSinceGnss, 0.0)
        assertTrue(after.eastMetres > before.eastMetres)
        assertTrue(after.northMetres > before.northMetres)
        assertTrue(after.horizontalUncertaintyMetres <
            before.horizontalUncertaintyMetres)
        assertFalse(dr.correctGnss(10_000.0, -10_000.0, 1.0, true))
        assertEquals(after.eastMetres, dr.estimate()!!.eastMetres, 0.0)
    }

    @Test fun invalidRawSensorUnitsCannotAdvanceDeadReckoning() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 180.0, 4.0)
        assertNull(dr.predict(1.0, 40.0, 0.0, false))
        assertNull(dr.predict(Double.NaN, 20.0, 0.0, true))
        assertNull(dr.predict(5.0, 20.0, 0.0, true))
        assertNull(dr.predict(1.0, Double.POSITIVE_INFINITY, 0.0, true))
        assertNull(dr.predict(1.0, 10.0, 100.0, true))
        assertEquals(0.0, dr.estimate()!!.secondsSinceGnss, 0.0)
    }

    @Test fun uninterruptedVerifiedVehicleMotionSurvivesLongGnssOutage() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 2.0))
        var beforeLimit: DeadReckoningEkf.Estimate? = null
        var pastLimit: DeadReckoningEkf.Estimate? = null
        // Fifteen minutes, far beyond the previous artificial 120s ceiling.
        repeat(900) { t ->
            val value = dr.predict(1.0, 20.0, 0.0, true)
            assertNotNull("DR stopped despite verified sensor data at second ${t + 1}", value)
            if (t == 119) beforeLimit = value
            if (t == 120) pastLimit = value
        }
        assertEquals(120.0, beforeLimit!!.secondsSinceGnss, 1e-9)
        assertEquals(121.0, pastLimit!!.secondsSinceGnss, 1e-9)
        val longPrediction = dr.estimate()!!
        assertTrue(longPrediction.estimated)
        assertEquals(900.0, longPrediction.secondsSinceGnss, 1e-9)
        assertEquals(18_000.0, longPrediction.northMetres, 0.01)
        assertTrue("Uncertainty must be reported, not clipped to 150m",
            longPrediction.horizontalUncertaintyMetres > 150.0)
        assertTrue(longPrediction.horizontalUncertaintyMetres.isFinite())
        dr.reset()
        assertNull(dr.estimate())
    }

    @Test fun accurateGnssFixCanCorrectAfterSeveralMinutesWithoutArtificialTimeout() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(20.0, 30.0, 0.0, 2.0))
        repeat(300) {
            assertNotNull(dr.predict(1.0, 10.0, 0.0, true))
        }
        val prior = dr.estimate()!!
        assertTrue(prior.secondsSinceGnss > 120.0)
        assertTrue(dr.correctGnss(20.0, 3_030.0, 2.0, genuineGnss = true))
        val after = dr.estimate()!!
        assertFalse(after.estimated)
        assertEquals(0.0, after.secondsSinceGnss, 0.0)
        assertEquals(3_030.0, after.northMetres, 1e-6)
    }

    @Test fun invalidSensorsPauseButDoNotEraseLongOutagePrediction() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 3.0))
        repeat(180) {
            assertNotNull(dr.predict(1.0, 8.0, 0.0, true))
        }
        val before = dr.estimate()!!
        assertNull(dr.predict(1.0, Double.NaN, 0.0, true))
        assertNull(dr.predict(1.0, 8.0, 0.0, false))
        val held = dr.estimate()!!
        assertEquals(before.northMetres, held.northMetres, 0.0)
        assertEquals(before.secondsSinceGnss, held.secondsSinceGnss, 0.0)
        val resumed = dr.predict(1.0, 8.0, 0.0, true)!!
        assertEquals(before.northMetres + 8.0, resumed.northMetres, 1e-8)
        assertTrue(resumed.horizontalUncertaintyMetres.isFinite())
    }

    @Test fun selectedGnssOriginIsHardGatedInSameTinyEkfEngine() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 0.0, 1.0)
        dr.predict(1.0, 8.0, 0.0, true)
        val before = dr.estimate()!!
        // Two independent coordinates cannot cross the selected source.
        assertFalse(dr.correctSelectedGnss(GpsSourceMode.CAR, GpsSourceMode.PHONE,
            0.0, 8.5, 1.0, sourceValidated = true))
        assertEquals(before.northMetres, dr.estimate()!!.northMetres, 0.0)
        assertTrue(dr.correctSelectedGnss(GpsSourceMode.CAR, GpsSourceMode.CAR,
            0.0, 8.5, 1.0, sourceValidated = true))
        assertFalse(dr.correctSelectedGnss(GpsSourceMode.PHONE, GpsSourceMode.CAR,
            0.0, 9.0, 1.0, sourceValidated = true))
        assertTrue(dr.correctSelectedGnss(GpsSourceMode.PHONE, GpsSourceMode.PHONE,
            0.0, 8.6, 1.0, sourceValidated = true))
    }

    @Test fun tinyEkfRejectsHugeReflectionJumpInsteadOfTeleport() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 0.0, 1.0)
        dr.predict(1.0, 10.0, 0.0, true)
        val original = dr.estimate()!!
        // 100m urban-canyon multipath while wheels/gyro predict 10m forward.
        assertFalse(dr.correctGnss(100.0, 10.0, 1.0, true))
        assertEquals(original.eastMetres, dr.estimate()!!.eastMetres, 0.0)
        assertEquals(original.northMetres, dr.estimate()!!.northMetres, 0.0)
        // A real GPS measurement consistent with the motion is still usable.
        assertTrue(dr.correctGnss(0.2, 10.2, 1.0, true))
        assertTrue(dr.estimate()!!.eastMetres < 1.0)
    }

    @Test fun urbanCanyonTenMetreJitterIsDownweightedInsideSameEkf() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 0.0, 1.0)
        dr.predict(1.0, 10.0, 0.0, true)
        // 10m with advertised 3m accuracy: moderate innovation, not reliable
        // enough for a 10m lateral teleport, but not an invalid location.
        val old = dr.estimate()!!
        assertTrue(dr.correctGnss(10.0, 10.0, 3.0, true))
        val fused = dr.estimate()!!
        assertTrue(fused.eastMetres > old.eastMetres)
        assertTrue(fused.eastMetres < 3.0)
        assertTrue(fused.horizontalUncertaintyMetres.isFinite())
        assertFalse(fused.estimated)
    }

    @Test fun independentVehicleAccelerationInflatesMotionCovarianceOnWheelSlip() {
        val consistent = DeadReckoningEkf()
        val disagrees = DeadReckoningEkf()
        consistent.anchor(0.0, 0.0, 0.0, 2.0)
        disagrees.anchor(0.0, 0.0, 0.0, 2.0)
        consistent.predict(1.0, 10.0, 0.0, true,
            longitudinalAccelerationMetresPerSecond2 = 0.0,
            accelerationVerified = true)
        disagrees.predict(1.0, 10.0, 0.0, true,
            longitudinalAccelerationMetresPerSecond2 = 0.0,
            accelerationVerified = true)
        consistent.predict(1.0, 15.0, 0.0, true,
            longitudinalAccelerationMetresPerSecond2 = 5.0,
            accelerationVerified = true)
        disagrees.predict(1.0, 15.0, 0.0, true,
            longitudinalAccelerationMetresPerSecond2 = 0.0,
            accelerationVerified = true)
        assertEquals(25.0, disagrees.estimate()!!.northMetres, 0.001)
        assertTrue(disagrees.estimate()!!.horizontalUncertaintyMetres >
            consistent.estimate()!!.horizontalUncertaintyMetres)
    }

    @Test fun unverifiedOrImpossibleAccelerometerNeverPoisonsPosition() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 0.0, 2.0)
        assertNull(dr.predict(1.0, 10.0, 0.0, true,
            longitudinalAccelerationMetresPerSecond2 = Double.NaN,
            accelerationVerified = true))
        assertNull(dr.predict(1.0, 10.0, 0.0, true,
            longitudinalAccelerationMetresPerSecond2 = 30.0,
            accelerationVerified = true))
        // Unverified raw vector is ignored, not treated as valid SI units.
        assertNotNull(dr.predict(1.0, 10.0, 0.0, true,
            longitudinalAccelerationMetresPerSecond2 = Double.NaN,
            accelerationVerified = false))
        assertEquals(10.0, dr.estimate()!!.northMetres, 0.0001)
    }

    @Test fun noCorrectionFromPredictedOrUnverifiedGps() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 30.0, 3.0)
        dr.predict(1.0, 5.0, 0.0, true)
        val old = dr.estimate()!!
        assertFalse(dr.correctGnss(5.0, 5.0, 2.0, genuineGnss = false))
        assertEquals(old.eastMetres, dr.estimate()!!.eastMetres, 0.0)
        assertTrue(abs(dr.estimate()!!.headingDegrees - 30.0) < 1e-6)
    }
    @Test fun missingVerifiedMotionFreezesPositionAndGrowsUncertainty() {
        val dr = DeadReckoningEkf()
        assertNull(dr.holdUnverifiedMotion(1.0))
        assertTrue(dr.anchor(100.0, -20.0, 45.0, 2.0))
        val anchored = dr.estimate()!!
        repeat(30) {
            val held = dr.holdUnverifiedMotion(1.0)!!
            assertEquals(anchored.eastMetres, held.eastMetres, 0.0)
            assertEquals(anchored.northMetres, held.northMetres, 0.0)
            assertEquals(anchored.headingDegrees, held.headingDegrees, 0.0)
        }
        val after = dr.estimate()!!
        assertEquals(30.0, after.secondsSinceGnss, 0.0)
        assertTrue(after.estimated)
        assertTrue(after.horizontalUncertaintyMetres >
            anchored.horizontalUncertaintyMetres)
        // No synthetic wheel path is accumulated during unverified motion.
        assertFalse(dr.correctGnss(200.0, 80.0, 2.0, true))
        assertEquals(100.0, dr.estimate()!!.eastMetres, 0.0)
    }

    @Test fun unboundedGnssAccuracyCannotConfirmEstimatedPosition() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 2.0))
        assertNotNull(dr.holdUnverifiedMotion(1.0))
        val before = dr.estimate()!!
        // A receiver reporting a 1km uncertainty is not a reliable
        // independent position observation, even if EKF NIS is permissive.
        assertFalse(dr.correctGnss(100.0, 0.0, 1000.0, genuineGnss = true))
        assertFalse(dr.correctGnss(0.0, 0.0, 0.1, genuineGnss = true))
        assertEquals(before.eastMetres, dr.estimate()!!.eastMetres, 0.0)
        assertEquals(before.northMetres, dr.estimate()!!.northMetres, 0.0)
        assertEquals(before.secondsSinceGnss, dr.estimate()!!.secondsSinceGnss, 0.0)
        assertTrue(dr.estimate()!!.estimated)
        // A well-characterised, consistent fix remains eligible.
        assertTrue(dr.correctGnss(0.1, 0.1, 2.0, genuineGnss = true))
        assertFalse(dr.estimate()!!.estimated)
    }

    @Test fun invalidHoldIntervalDoesNotAdvanceTimeOrPosition() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 0.0, 1.0)
        for (bad in listOf(Double.NaN, -1.0, 0.0, 2.0)) {
            assertNull(dr.holdUnverifiedMotion(bad))
        }
        assertEquals(0.0, dr.estimate()!!.secondsSinceGnss, 0.0)
        assertEquals(0.0, dr.estimate()!!.northMetres, 0.0)
    }

    @Test fun staleTimestampCannotReanchorOrRewindGnssAfterCorrection() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 2.0, measurementTimestampMs = 10_000L))
        assertNotNull(dr.predict(1.0, 2.0, 0.0, true))
        val before = dr.estimate()!!
        assertFalse(dr.anchor(50.0, 50.0, 90.0, 2.0, measurementTimestampMs = 9_000L))
        assertFalse(dr.anchor(50.0, 50.0, 90.0, 2.0, measurementTimestampMs = 10_000L))
        assertFalse(dr.anchor(50.0, 50.0, 90.0, 2.0, measurementTimestampMs = 0L))
        assertEquals(before.northMetres, dr.estimate()!!.northMetres, 0.0)
        assertEquals(before.secondsSinceGnss, dr.estimate()!!.secondsSinceGnss, 0.0)
        assertTrue(dr.correctGnss(0.0, 2.1, 2.0, true, measurementTimestampMs = 11_000L))
        assertFalse(dr.anchor(50.0, 50.0, 90.0, 2.0, measurementTimestampMs = 10_500L))
        assertTrue(dr.anchor(0.0, 2.2, 0.0, 2.0, measurementTimestampMs = 12_000L))
    }

    @Test fun staleGnssCorrectionCannotRewindAcceptedMeasurementTimestamp() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 2.0, measurementTimestampMs = 10_000L))
        // A new correction, unlike anchor(), must enforce the same monotonic clock.
        assertTrue(dr.correctGnss(0.0, 0.1, 2.0, true, measurementTimestampMs = 11_000L))
        val accepted = dr.estimate()!!
        assertFalse(dr.correctGnss(0.0, 0.2, 2.0, true, measurementTimestampMs = 10_500L))
        assertFalse(dr.correctGnss(0.0, 0.2, 2.0, true, measurementTimestampMs = 11_000L))
        assertFalse(dr.correctGnss(0.0, 0.2, 2.0, true, measurementTimestampMs = 0L))
        assertEquals(accepted.northMetres, dr.estimate()!!.northMetres, 0.0)
        assertTrue(dr.correctGnss(0.0, 0.3, 2.0, true, measurementTimestampMs = 12_000L))
    }

    @Test fun missingTimestampCannotBypassMonotonicGnssClock() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 2.0, measurementTimestampMs = 10_000L))
        val before = dr.estimate()!!
        assertFalse(dr.correctGnss(0.0, 0.1, 2.0, true, measurementTimestampMs = null))
        assertFalse(dr.anchor(50.0, 50.0, 0.0, 2.0, measurementTimestampMs = null))
        assertEquals(before.northMetres, dr.estimate()!!.northMetres, 0.0)
        assertTrue(dr.correctGnss(0.0, 0.1, 2.0, true, measurementTimestampMs = 11_000L))
        assertFalse(dr.correctGnss(0.0, 0.2, 2.0, true, measurementTimestampMs = null))
        assertTrue(dr.correctGnss(0.0, 0.2, 2.0, true, measurementTimestampMs = 12_000L))
    }


}
