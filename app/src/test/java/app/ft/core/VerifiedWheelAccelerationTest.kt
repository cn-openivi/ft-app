package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class VerifiedWheelAccelerationTest {
    @Test fun constantVerifiedWheelSpeedNeedsNoAccelerometer() {
        val estimator = VerifiedWheelAcceleration()
        assertNull(estimator.observe(1_000L, 20.0, true))
        assertEquals(0.0, estimator.observe(1_100L, 20.0, true)!!, 0.00001)
        assertEquals(0.2, estimator.observe(1_200L, 20.02, true)!!, 0.00001)
    }

    @Test fun invalidUntrustedAndReorderedSamplesCannotTrain() {
        val estimator = VerifiedWheelAcceleration()
        assertNull(estimator.observe(1_000L, 20.0, false))
        assertNull(estimator.observe(1_100L, 20.0, true))
        assertNull(estimator.observe(1_000L, 20.0, true))
        assertNull(estimator.observe(1_200L, 20.0, true))
        assertEquals(0.0, estimator.observe(1_300L, 20.0, true)!!, 0.00001)
    }

    @Test fun staleOrBackwardsWheelEpochRequiresFreshPair() {
        val estimator = VerifiedWheelAcceleration()
        assertNull(estimator.observe(1_000L, 20.0, true))
        assertNull(estimator.observe(2_000L, 20.0, true))
        assertEquals(0.0, estimator.observe(2_100L, 20.0, true)!!, 0.00001)
        assertNull(estimator.observe(2_050L, 20.0, true))
        assertNull(estimator.observe(2_200L, 20.0, true))
        assertEquals(0.0, estimator.observe(2_300L, 20.0, true)!!, 0.00001)
    }
}
