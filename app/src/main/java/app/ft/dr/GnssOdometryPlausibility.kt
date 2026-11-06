package app.ft.dr

import kotlin.math.hypot
import kotlin.math.max

/**
 * A GNSS observation admission rule based on VERIFIED vehicle wheel/yaw,
 * independent of TinyEKF's increasingly uncertain position covariance.
 *
 * This prevents a large, sustained reflected GNSS displacement from
 * becoming admissible solely because an underground blackout inflated
 * the Kalman covariance. It is NOT a second state estimator.
 *
 * Model inspiration: FusionCore's GNSS blackout/coasting regression
 * (Apache-2.0). This source code was independently implemented for FT.
 */
internal class GnssOdometryPlausibility {
    private var lastAcceptedEast = 0.0
    private var lastAcceptedNorth = 0.0
    private var cumulativeVerifiedWheelMetres = 0.0
    private var anchored = false
    private var observedVerifiedWheel = false
    private var unverifiedMotionSinceAnchor = false

    fun reset(east: Double, north: Double) {
        require(east.isFinite() && north.isFinite())
        lastAcceptedEast = east
        lastAcceptedNorth = north
        cumulativeVerifiedWheelMetres = 0.0
        observedVerifiedWheel = false
        unverifiedMotionSinceAnchor = false
        anchored = true
    }

    fun onVerifiedMotion(distanceMetres: Double) {
        if (!anchored || !distanceMetres.isFinite() || distanceMetres < 0.0) return
        cumulativeVerifiedWheelMetres += distanceMetres
        observedVerifiedWheel = true
    }

    /** A missing trusted wheel/gyro interval makes travelled distance unknowable. */
    fun onUnverifiedMotion() {
        if (anchored) unverifiedMotionSinceAnchor = true
    }

    /**
     * Wheel path length bounds straight-line displacement and the same
     * gyro-integrated path provides an independent endpoint estimate.
     * Neither bound relaxes solely because EKF covariance grows.
     *
     * After long GPS gaps, allowance scales with verified travelled path
     * to account for wheel-calibration/yaw drift. It never imposes a hard
     * timeout or a maximum distance for DR continuation.
     */
    fun permits(
        candidateEast: Double,
        candidateNorth: Double,
        predictedEast: Double,
        predictedNorth: Double
    ): Boolean {
        if (!candidateEast.isFinite() || !candidateNorth.isFinite() ||
            !predictedEast.isFinite() || !predictedNorth.isFinite()) return false
        if (!anchored) return false
        // No independent displacement proof exists after an untrusted gap.
        // Admit only a near-anchor fix; never treat grown EKF covariance
        // or resumed wheel ticks as evidence of motion during that gap.
        if (unverifiedMotionSinceAnchor || !observedVerifiedWheel) {
            val displacement = hypot(candidateEast - lastAcceptedEast,
                candidateNorth - lastAcceptedNorth)
            val predictionError = hypot(candidateEast - predictedEast,
                candidateNorth - predictedNorth)
            return displacement <= 12.0 && predictionError <= 14.0
        }

        val travelled = cumulativeVerifiedWheelMetres
        if (!travelled.isFinite()) return false
        val displacement = hypot(candidateEast - lastAcceptedEast,
            candidateNorth - lastAcceptedNorth)
        val predictionError = hypot(candidateEast - predictedEast,
            candidateNorth - predictedNorth)
        // The vehicle cannot travel farther than its wheel path, apart
        // from a small GNSS/odometry margin; unlike NIS, this does not
        // depend on the EKF's grown covariance.
        val displacementAllowance = travelled * 1.05 + 12.0
        val predictedAllowance = max(14.0, travelled * 0.04 + 10.0)
        return displacement.isFinite() && predictionError.isFinite() &&
            displacement <= displacementAllowance &&
            predictionError <= predictedAllowance
    }

    fun confirmed(fusedEast: Double, fusedNorth: Double) {
        reset(fusedEast, fusedNorth)
    }
}
