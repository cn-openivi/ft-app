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

    @Test fun frequencyEstimateUsesNewValidFramesAndUiThrottlePreservesCounts() {
        VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, MotionVector(1.0, 2.0, 3.0), 10_000L)
        val second = VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, MotionVector(1.0, 2.0, 3.0), 10_100L)
        assertEquals(10.0, second.approximateHz!!, 0.001)
        val third = VehicleMotionTelemetry.accept(MotionSource.ICCOA,
            MotionKind.GYROSCOPE, MotionVector(1.0, 2.0, 3.0), 10_300L)
        assertEquals(3L, third.accepted)
        assertEquals(9.0, third.approximateHz!!, 0.001)
        assertEquals(3L, VehicleMotionTelemetry.current.value
            .getValue(MotionSource.ICCOA).gyro.accepted)
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
