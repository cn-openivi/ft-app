package app.ft.dr

import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic input-only tests. These prove GNSS admission behavior
 * with normalized synthetic OEM odometry, not a working vehicle DR.
 */
class GnssAdmissionGatesTest {
    @Test fun repeatedTenMetreReflectionIsDeniedDespiteWeakReceiverAccuracy() {
        val gate = GnssContinuityGate()
        for (i in 1..5) {
            assertTrue(gate.permits(i * 1000L, 0.0, 0.0, 0.0, 0.0))
            gate.confirmed(i * 1000L, 0.0, 0.0, 0.0, 0.0)
        }
        for (i in 6..20) {
            assertFalse("Reflected fix was accepted at $i",
                gate.permits(i * 1000L, 10.0, 0.0, 0.0, 0.0))
        }
        assertTrue(gate.permits(21_000L, 0.5, 0.5, 0.0, 0.0))
    }

    @Test fun turningCarIsAdmittedIfVerifiedWheelGyroPredictionMatches() {
        val gate = GnssContinuityGate()
        for (i in 1..5) gate.confirmed(i * 1000L,
            0.0, i * 10.0, 0.0, i * 10.0)
        // Straight-line history expects more north, but the actual
        // car has turned right; verified wheel/yaw prediction agrees.
        assertTrue(gate.permits(6000L, 10.0, 50.0, 10.0, 50.0))
    }

    @Test fun realGpsReturnsAfterTunnelWithoutStaleContinuityHistory() {
        val gate = GnssContinuityGate()
        for (i in 1..5) gate.confirmed(i * 1000L, 0.0, i * 10.0,
            0.0, i * 10.0)
        assertTrue(gate.permits(80_000L, 40.0, 490.0, 40.0, 490.0))
        gate.confirmed(80_000L, 40.0, 490.0, 40.0, 490.0)
        assertTrue(gate.permits(81_000L, 41.0, 499.0, 41.0, 499.0))
        assertFalse(gate.permits(80_500L, 42.0, 490.0, 42.0, 490.0))
    }

    @Test fun wheelPhysicsDoesNotRelaxAsKalmanCovarianceGrows() {
        val gate = GnssOdometryPlausibility()
        gate.reset(0.0, 0.0)
        repeat(8) { gate.onVerifiedMotion(10.0) }
        assertFalse(gate.permits(60.0, 80.0, 0.0, 80.0))
        assertTrue(gate.permits(1.0, 81.0, 0.0, 80.0))
    }

    @Test fun curvedTunnelCanMoveLessThanWheelPathAndRejoin() {
        val gate = GnssOdometryPlausibility()
        gate.reset(100.0, 200.0)
        repeat(80) { gate.onVerifiedMotion(12.0) }
        // Curving road displacement is less than total wheel path.
        assertTrue(gate.permits(500.0, 600.0, 495.0, 605.0))
        gate.confirmed(499.0, 601.0)
        gate.onVerifiedMotion(12.0)
        assertTrue(gate.permits(499.2, 612.0, 499.0, 613.0))
    }

    @Test fun rejectedFixMustNeverMoveOdometryTrustAnchor() {
        val gate = GnssOdometryPlausibility()
        gate.reset(0.0, 0.0)
        repeat(10) { gate.onVerifiedMotion(10.0) }
        repeat(30) {
            assertFalse(gate.permits(200.0 + it, 200.0, 0.0, 100.0))
        }
        assertTrue(gate.permits(2.0, 99.0, 0.0, 100.0))
    }

    @Test fun invalidUnknownMotionDoesNotCreateFalseTravelDistance() {
        val gate = GnssOdometryPlausibility()
        gate.reset(0.0, 0.0)
        gate.onVerifiedMotion(Double.NaN)
        gate.onVerifiedMotion(-10.0)
        gate.onVerifiedMotion(0.0)
        assertFalse(gate.permits(100.0, 0.0, 0.0, 0.0))
        assertTrue(gate.permits(0.5, 0.0, 0.0, 0.0))
    }
}
