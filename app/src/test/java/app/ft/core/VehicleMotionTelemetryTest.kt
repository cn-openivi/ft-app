package app.ft.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VehicleMotionTelemetryTest {
    @After fun reset() {
        VehicleMotionTelemetry.clear(MotionSource.ICCOA)
        VehicleMotionTelemetry.clear(MotionSource.BAIDU)
    }

    @Test fun iccoaGyroAccelAndBaiduAreIndependent() {
        val v = MotionVector(1.0, -2.0, 3.5, 1000L)
        val g = VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, v, 10_000L)
        assertEquals(1L, g.accepted)
        assertTrue(g.fresh(10_000L))
        VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.ACCELERATION, MotionVector(2.0, 0.0, 0.0), 10_300L)
        VehicleMotionTelemetry.accept(MotionSource.BAIDU,
            MotionKind.GYROSCOPE, MotionVector(100.0, 0.0, 0.0), 10_600L)
        val all = VehicleMotionTelemetry.current.value
        assertEquals(1.0, all.getValue(MotionSource.ICCOA).gyro.vector!!.x, 0.0)
        assertEquals(2.0, all.getValue(MotionSource.ICCOA).acceleration.vector!!.x, 0.0)
        assertEquals(100.0, all.getValue(MotionSource.BAIDU).gyro.vector!!.x, 0.0)
    }

    @Test fun badFramesDoNotEraseLastValidValueOrClaimFreshness() {
        VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, MotionVector(1.0, 2.0, 3.0), 10_000L)
        val state = VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, null, 11_000L)
        assertEquals(1L, state.accepted)
        assertEquals(1L, state.rejected)
        assertEquals(1.0, state.vector!!.x, 0.0)
        assertFalse(state.fresh(14_000L))
        assertEquals(4_000L, state.ageMs(14_000L))
    }

    @Test fun actualImuRateUsesOneSecondSampleWindowNotInterarrivalSpike() {
        // A single 1 ms pair is NOT 1000 Hz sustained sensor evidence.
        val vector = MotionVector(1.0, 2.0, 3.0)
        VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, vector, 10_000L)
        val burst = VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, vector, 10_001L)
        assertNull(burst.approximateHz)

        // Actual 10 Hz cadence over one second, plus one extra burst:
        // the elapsed-window estimate is ~11 Hz, NOT 1000 Hz.
        for (t in 10_100L..10_900L step 100L) {
            VehicleMotionTelemetry.accept(MotionSource.ICCOA,
                MotionKind.GYROSCOPE, vector, t)
        }
        val measured = VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, vector, 11_000L)
        assertEquals(12L, measured.accepted)
        assertEquals(11.0, measured.approximateHz!!, 0.001)
        assertEquals(12L, VehicleMotionTelemetry.current.value
            .getValue(MotionSource.ICCOA).gyro.accepted)
    }

    @Test fun invalidFramesAndLongGapsCannotMasqueradeAsCurrentFrequency() {
        val vector = MotionVector(1.0, 2.0, 3.0)
        VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, vector, 20_000L)
        for (t in 20_100L..21_000L step 100L)
            VehicleMotionTelemetry.accept(MotionSource.ICCOA,
                MotionKind.GYROSCOPE, vector, t)
        val invalid = VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, null, 21_100L)
        assertEquals(10.0, invalid.approximateHz!!, 0.001)
        assertEquals(1L, invalid.rejected)
        // Reconnect / stalled stream clears the old sample rate.
        val resumed = VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, vector, 25_000L)
        assertNull(resumed.approximateHz)
        assertEquals(12L, resumed.accepted)
    }

    @Test fun clearingOneProtocolDoesNotClearTheOther() {
        VehicleMotionTelemetry.accept(MotionSource.ICCOA, MotionKind.ACCELERATION,
            MotionVector(1.0, 0.0, 0.0), 10_000L)
        VehicleMotionTelemetry.accept(MotionSource.BAIDU, MotionKind.ACCELERATION,
            MotionVector(2.0, 0.0, 0.0), 10_000L)
        VehicleMotionTelemetry.clear(MotionSource.ICCOA)
        assertFalse(VehicleMotionTelemetry.current.value.containsKey(MotionSource.ICCOA))
        assertTrue(VehicleMotionTelemetry.current.value.containsKey(MotionSource.BAIDU))
    }
}
