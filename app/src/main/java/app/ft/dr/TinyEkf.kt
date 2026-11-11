package app.ft.dr

import kotlin.math.sqrt

/**
 * Kotlin/JVM adaptation of Simon D. Levy's TinyEKF (MIT).
 * Upstream: https://github.com/simondlevy/TinyEKF
 * Pinned source: ba0d2a90e22f2f4e4120a91cb05ca2ca6a8e8da3
 *
 * The unmodified original header and complete MIT license are retained in
 * third_party/TinyEKF/. Prediction and measurement update follow TinyEKF's
 * ekf_predict / ekf_update matrix equations, with double precision, bounded
 * matrix dimensions, SPD Cholesky inversion and covariance symmetrization.
 *
 * Pure Kotlin is intentional: avoids loading a JNI library, extra NDK/ABI
 * dependencies and device incompatibilities on the FT phone.
 */
internal class TinyEkf(
    private val stateSize: Int,
    private val observationSize: Int
) {
    init {
        require(stateSize in 1..8 && observationSize in 1..8)
    }

    private var x = DoubleArray(stateSize)
    private var p = DoubleArray(stateSize * stateSize)

    @Synchronized
    fun state(): DoubleArray = x.copyOf()
    @Synchronized
    fun covariance(): DoubleArray = p.copyOf()

    @Synchronized
    fun reset(initialState: DoubleArray, covarianceDiagonal: DoubleArray) {
        require(initialState.size == stateSize)
        require(covarianceDiagonal.size == stateSize)
        require(initialState.all(Double::isFinite))
        require(covarianceDiagonal.all { it.isFinite() && it >= 0.0 })
        x = initialState.copyOf()
        p = DoubleArray(stateSize * stateSize) { i ->
            if (i / stateSize == i % stateSize) covarianceDiagonal[i / stateSize] else 0.0
        }
    }

    /**
     * TinyEKF: x = f(x, u), P = F P F' + Q.
     * Inputs are row-major, no mutable array is retained by this class.
     */
    @Synchronized
    fun predict(
        predictedState: DoubleArray,
        jacobian: DoubleArray,
        processNoise: DoubleArray
    ) {
        val n = stateSize
        require(predictedState.size == n && jacobian.size == n * n &&
            processNoise.size == n * n)
        require(predictedState.all(Double::isFinite) &&
            jacobian.all(Double::isFinite) && processNoise.all(Double::isFinite))
        val fp = multiply(jacobian, p, n, n, n)
        val fpf = multiply(fp, transpose(jacobian, n, n), n, n, n)
        val nextP = DoubleArray(n * n) { i -> fpf[i] + processNoise[i] }
        require(nextP.all(Double::isFinite))
        x = predictedState.copyOf()
        p = symmetric(nextP, n)
    }

    /**
     * TinyEKF: G = P H' (H P H' + R)^-1
     * x = x + G(z - h(x)).
     *
     * Covariance uses the Joseph form P=(I-KH)P(I-KH)' + KRK'
     * as in robot_localization (BSD). This is algebraically identical to
     * the simplified update, but better preserves positive covariance under
     * many successive GNSS corrections. No extra measurement gate or timer.
     * Returns false for a singular covariance without corrupting state.
     */
    @Synchronized
    fun update(
        measurement: DoubleArray,
        expectedMeasurement: DoubleArray,
        observationJacobian: DoubleArray,
        observationNoise: DoubleArray
    ): Boolean {
        val n = stateSize
        val m = observationSize
        require(measurement.size == m && expectedMeasurement.size == m)
        require(observationJacobian.size == m * n && observationNoise.size == m * m)
        require(measurement.all(Double::isFinite) &&
            expectedMeasurement.all(Double::isFinite) &&
            observationJacobian.all(Double::isFinite) &&
            observationNoise.all(Double::isFinite))

        val ht = transpose(observationJacobian, m, n)
        val pht = multiply(p, ht, n, n, m)
        val hpht = multiply(observationJacobian, pht, m, n, m)
        val s = DoubleArray(m * m) { i -> hpht[i] + observationNoise[i] }
        val inv = inversePositiveDefinite(s, m) ?: return false
        val gain = multiply(pht, inv, n, m, m)
        val innovation = DoubleArray(m) { i -> measurement[i] - expectedMeasurement[i] }
        val nextX = x.copyOf()
        for (i in 0 until n) {
            for (j in 0 until m) nextX[i] += gain[i * m + j] * innovation[j]
        }

        val gh = multiply(gain, observationJacobian, n, m, n)
        val iMinusGh = DoubleArray(n * n) { i ->
            (if (i / n == i % n) 1.0 else 0.0) - gh[i]
        }
        // Stable Joseph covariance form, using the same gain and measurement.
        // Compute entirely before committing state so a numerical error leaves
        // the original EKF untouched.
        val residualP = multiply(iMinusGh, p, n, n, n)
        val josephP = multiply(residualP, transpose(iMinusGh, n, n), n, n, n)
        val gainR = multiply(gain, observationNoise, n, m, m)
        val gainRGainT = multiply(gainR, transpose(gain, n, m), n, m, n)
        val nextP = symmetric(
            DoubleArray(n * n) { i -> josephP[i] + gainRGainT[i] }, n)
        if (!nextX.all(Double::isFinite) || !nextP.all(Double::isFinite) ||
            (0 until n).any { nextP[it * n + it] < -1e-9 }) return false
        x = nextX
        p = nextP
        return true
    }

    private fun multiply(
        a: DoubleArray, b: DoubleArray, rows: Int, inner: Int, columns: Int
    ): DoubleArray {
        val out = DoubleArray(rows * columns)
        for (i in 0 until rows) for (j in 0 until columns) {
            var value = 0.0
            for (k in 0 until inner) value += a[i * inner + k] * b[k * columns + j]
            out[i * columns + j] = value
        }
        return out
    }

    private fun transpose(a: DoubleArray, rows: Int, columns: Int): DoubleArray {
        val out = DoubleArray(rows * columns)
        for (i in 0 until rows) for (j in 0 until columns) {
            out[j * rows + i] = a[i * columns + j]
        }
        return out
    }

    private fun symmetric(a: DoubleArray, n: Int): DoubleArray {
        val out = a.copyOf()
        for (i in 0 until n) for (j in i + 1 until n) {
            val mid = (a[i * n + j] + a[j * n + i]) / 2.0
            out[i * n + j] = mid
            out[j * n + i] = mid
        }
        return out
    }

    /** TinyEKF's SPD inversion uses Cholesky; this is an equivalent solver. */
    private fun inversePositiveDefinite(a: DoubleArray, n: Int): DoubleArray? {
        val l = DoubleArray(n * n)
        for (i in 0 until n) for (j in 0..i) {
            var sum = a[i * n + j]
            for (k in 0 until j) sum -= l[i * n + k] * l[j * n + k]
            if (i == j) {
                if (sum <= 1e-15 || !sum.isFinite()) return null
                l[i * n + i] = sqrt(sum)
            } else {
                l[i * n + j] = sum / l[j * n + j]
            }
        }
        val inverse = DoubleArray(n * n)
        for (column in 0 until n) {
            val y = DoubleArray(n)
            for (i in 0 until n) {
                var t = if (i == column) 1.0 else 0.0
                for (k in 0 until i) t -= l[i * n + k] * y[k]
                y[i] = t / l[i * n + i]
            }
            val solved = DoubleArray(n)
            for (i in n - 1 downTo 0) {
                var t = y[i]
                for (k in i + 1 until n) t -= l[k * n + i] * solved[k]
                solved[i] = t / l[i * n + i]
            }
            for (i in 0 until n) inverse[i * n + column] = solved[i]
        }
        return inverse.takeIf { it.all(Double::isFinite) }
    }
}
