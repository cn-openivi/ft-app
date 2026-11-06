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
        // permits() is a precheck, not a timestamp commitment. The
        // candidate at 80_500 remains newer than confirmed 80_000.
        assertTrue(gate.permits(80_500L, 40.5, 494.5, 40.5, 494.5))
        // Only a successful downstream EKF update may advance the floor.
        gate.confirmed(81_000L, 41.0, 499.0, 41.0, 499.0)
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
    @Test fun rejectedReflectionCannotRewindOrderingFloorForValidFix() {
        val gate = GnssContinuityGate()
        for (i in 1..5) gate.confirmed(i * 1000L, 0.0, 0.0, 0.0, 0.0)
        // 10m reflected fix at 6500 is rejected, but its timestamp must
        // not reject the valid 6200 fix, which is newer than confirmed 5000.
        assertFalse(gate.permits(6_500L, 10.0, 0.0, 0.0, 0.0))
        assertTrue(gate.permits(6_200L, 0.0, 0.0, 0.0, 0.0))
        gate.confirmed(6_200L, 0.0, 0.0, 0.0, 0.0)
        assertFalse(gate.permits(6_100L, 0.0, 0.0, 0.0, 0.0))
        // A later rejected reflection must still be denied.
        assertFalse(gate.permits(6_700L, 10.0, 0.0, 0.0, 0.0))
    }

    @Test fun unconfirmedFutureTimestampDoesNotPoisonLaterRealGnss() {
        val gate = GnssContinuityGate()
        for (i in 1..5) {
            gate.confirmed(i * 1000L, 0.0, 0.0, 0.0, 0.0)
        }
        // The candidate passes continuity precheck, but the caller's
        // subsequent EKF/odometry check rejects it: no confirmed() call.
        assertTrue(gate.permits(1_000_000L, 0.0, 0.0, 0.0, 0.0))
        assertTrue(gate.permits(6_000L, 0.0, 0.0, 0.0, 0.0))
        gate.confirmed(6_000L, 0.0, 0.0, 0.0, 0.0)
        assertFalse(gate.permits(5_500L, 0.0, 0.0, 0.0, 0.0))
    }

    @Test fun outOfOrderRejectedReflectionCannotManufactureGnssGap() {
        val gate = GnssContinuityGate()
        for (i in 1..5) gate.confirmed(i * 1000L, 0.0, 0.0, 0.0, 0.0)
        // Continuous reflected samples remain rejected and advance only
        // the observation clock, not the confirmed GNSS position.
        for (t in listOf(6_000L, 7_000L, 8_000L, 9_000L)) {
            assertFalse(gate.permits(t, 10.0, 0.0, 0.0, 0.0))
        }
        // A delayed rejected packet must not rewind the observation clock.
        assertFalse(gate.permits(6_000L, 10.0, 0.0, 0.0, 0.0))
        // Without the monotonic guard, 9500-6000 > 3000 falsely
        // declares a blackout and admits the same 10m reflection.
        assertFalse(gate.permits(9_500L, 10.0, 0.0, 0.0, 0.0))
        assertTrue(gate.permits(9_600L, 0.0, 0.0, 0.0, 0.0))
    }

}
