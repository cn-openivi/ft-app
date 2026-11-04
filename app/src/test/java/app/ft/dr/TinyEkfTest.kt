package app.ft.dr

import org.junit.Assert.*
import org.junit.Test

class TinyEkfTest {
    @Test fun predictMatchesPinnedTinyEkfLinearEquations() {
        val ekf = TinyEkf(2, 1)
        ekf.reset(doubleArrayOf(1.0, 2.0), doubleArrayOf(4.0, 9.0))
        ekf.predict(
            doubleArrayOf(3.0, 2.0),
            doubleArrayOf(1.0, 1.0, 0.0, 1.0),
            doubleArrayOf(1.0, 0.0, 0.0, 2.0)
        )
        assertArrayEquals(doubleArrayOf(3.0, 2.0), ekf.state(), 1e-10)
        // F * diag(4,9) * F' + diag(1,2) = [14,9;9,11].
        assertArrayEquals(doubleArrayOf(14.0, 9.0, 9.0, 11.0),
            ekf.covariance(), 1e-10)
    }

    @Test fun updateMatchesKalmanGainAndShrinksCovariance() {
        val ekf = TinyEkf(2, 2)
        ekf.reset(doubleArrayOf(0.0, 0.0), doubleArrayOf(4.0, 4.0))
        assertTrue(ekf.update(
            measurement = doubleArrayOf(10.0, 20.0),
            expectedMeasurement = doubleArrayOf(0.0, 0.0),
            observationJacobian = doubleArrayOf(1.0, 0.0, 0.0, 1.0),
            observationNoise = doubleArrayOf(1.0, 0.0, 0.0, 1.0)
        ))
        // Gain = 4/(4+1) = 0.8; covariance (1-K)P = 0.8.
        assertArrayEquals(doubleArrayOf(8.0, 16.0), ekf.state(), 1e-10)
        assertArrayEquals(doubleArrayOf(0.8, 0.0, 0.0, 0.8),
            ekf.covariance(), 1e-10)
    }

    @Test fun singularInnovationNeverCorruptsFilterState() {
        val ekf = TinyEkf(2, 2)
        ekf.reset(doubleArrayOf(7.0, -3.0), doubleArrayOf(0.0, 0.0))
        assertFalse(ekf.update(
            measurement = doubleArrayOf(100.0, 200.0),
            expectedMeasurement = doubleArrayOf(7.0, -3.0),
            observationJacobian = doubleArrayOf(1.0, 0.0, 0.0, 1.0),
            observationNoise = DoubleArray(4)
        ))
        assertArrayEquals(doubleArrayOf(7.0, -3.0), ekf.state(), 0.0)
        assertArrayEquals(DoubleArray(4), ekf.covariance(), 0.0)
    }

    @Test fun callerArraysAreNotAliased() {
        val ekf = TinyEkf(2, 1)
        val state = doubleArrayOf(1.0, 2.0)
        ekf.reset(state, doubleArrayOf(1.0, 1.0))
        state[0] = 100.0
        val snapshot = ekf.state()
        snapshot[1] = 200.0
        assertArrayEquals(doubleArrayOf(1.0, 2.0), ekf.state(), 0.0)
    }
}
