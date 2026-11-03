package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class WheelSpeedAutoCalibrationTest {
    private fun sample(
        t: Long,
        wheel: Double = 72.0,
        gps: Double = 75.6,
        accuracy: Double = 0.25,
        yaw: Double = 0.0,
        acceleration: Double = 0.0,
        unitsKnown: Boolean = true
    ) = WheelSpeedAutoCalibration.Observation(
        monotonicTimeMs = t,
        vehicleSpeedKmh = wheel,
        independentGnssSpeedKmh = gps,
        gnssSpeedAccuracyMps = accuracy,
        yawRateDegreesPerSecond = yaw,
        accelerationMps2 = acceleration,
        wheelUnitsConfirmedKmh = unitsKnown
    )

    @Test fun neverCalibratesWithoutConfirmedWheelUnits() {
        val c = WheelSpeedAutoCalibration(minimumSamples = 5)
        repeat(20) { c.observe(sample((it + 1) * 1000L, unitsKnown = false)) }
        assertEquals(0, c.estimate().acceptedSamples)
        assertEquals(1.0, c.estimate().multiplier, 0.0)
        assertNull(c.correctedMps(72.0))
    }

    @Test fun learnsRobustCorrectionInNormalDriveWithoutDriverControls() {
        val c = WheelSpeedAutoCalibration(minimumSamples = 5)
        repeat(5) { c.observe(sample((it + 1) * 1000L)) }
        val estimate = c.estimate()
        assertTrue(estimate.confident)
        assertEquals(5, estimate.acceptedSamples)
        assertEquals(1.05, estimate.multiplier, 0.00001)
        assertEquals(21.0, c.correctedMps(72.0)!!, 0.00001)
    }

    @Test fun turnsBrakingPoorGnssAndOutliersCannotChangeCalibration() {
        val c = WheelSpeedAutoCalibration(minimumSamples = 3)
        c.observe(sample(1000L))
        c.observe(sample(2000L))
        c.observe(sample(3000L))
        val old = c.estimate().multiplier
        c.observe(sample(4000L, accuracy = 1.0))
        c.observe(sample(5000L, yaw = 3.0))
        c.observe(sample(6000L, acceleration = 0.5))
        c.observe(sample(7000L, wheel = 0.0, gps = 0.0))
        c.observe(sample(8000L, wheel = 72.0, gps = 93.0))
        assertEquals(3, c.estimate().acceptedSamples)
        assertEquals(old, c.estimate().multiplier, 0.0)
    }

    @Test fun calibrationFreezesDuringGnssOutageAndReinitializesForOtherCar() {
        val c = WheelSpeedAutoCalibration(minimumSamples = 3)
        repeat(3) { c.observe(sample((it + 1) * 1000L)) }
        val previous = c.estimate()
        // No eligible GNSS observation occurs underground.
        assertEquals(previous, c.estimate())
        c.reset()
        assertNull(c.correctedMps(72.0))
        assertFalse(c.estimate().confident)
        assertEquals(1.0, c.estimate().multiplier, 0.0)
    }

    @Test fun samplingGateDoesNotCountDuplicateOrOutOfOrderTimestamps() {
        val c = WheelSpeedAutoCalibration(minimumSamples = 3)
        c.observe(sample(10_000L))
        c.observe(sample(10_300L))
        assertEquals(1, c.estimate().acceptedSamples)
        c.observe(sample(9_000L)) // monotonic clock reset
        assertEquals(1, c.estimate().acceptedSamples)
        assertEquals(1.0, c.estimate().multiplier, 0.0)
    }
}
