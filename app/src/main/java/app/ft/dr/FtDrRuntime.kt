package app.ft.dr

import android.content.Context
import app.ft.core.AndroidWheelCalibrationStorage
import app.ft.core.GpsSourceMode
import app.ft.core.WheelCalibrationStore
import app.ft.core.WheelSpeedAutoCalibration
import app.ft.core.VerifiedWheelAcceleration
import kotlin.math.abs

/**
 * Single in-process verified position owner for FT and subscribed Android Auto.
 * No existing ICCOA raw message is automatically granted verified provenance.
 * The owner-controlled bridge must authenticate a STABLE vehicle identity,
 * independently verify the wheel/yaw/accuracy/clock domains, then invoke these
 * methods. A rotating BLE MAC or FT's virtual AA identity is NOT a car ID.
 */
internal object FtDrRuntime {
    private var verifiedVehicleId: String? = null
    private var wheelCalibration: WheelSpeedAutoCalibration? = null
    private var calibrationStore: WheelCalibrationStore? = null
    private val wheelAcceleration = VerifiedWheelAcceleration()

    private val fusion = FtDrFusionEngine { latitude, longitude, estimate, emittedAtMs ->
        DrNavigationTelemetry.publishFromVerifiedEkf(
            referenceLatitude = latitude,
            referenceLongitude = longitude,
            estimate = estimate,
            gpsSourceMode = GpsSourceMode.CAR,
            wheelSpeedAndYawVerified = true,
            producedAtMs = emittedAtMs
        )
    }

    /** Must be called only after an independently verified car-auth identity. */
    @Synchronized
    fun bindVerifiedVehicle(
        context: Context, vehicleId: String, independentlyVerifiedIdentity: Boolean
    ): Boolean {
        if (!independentlyVerifiedIdentity || vehicleId.isBlank() ||
            vehicleId.length > 256) return false
        if (verifiedVehicleId == vehicleId) return true
        val changingCar = verifiedVehicleId != null
        reset()
        // A different authenticated vehicle is an explicit authority reset.
        // Never show the previous car's filtered coordinates for the next car.
        if (changingCar) FtPositionTelemetry.clear()
        val store = WheelCalibrationStore(AndroidWheelCalibrationStorage(context))
        val calibration = WheelSpeedAutoCalibration()
        if (!calibration.bindVehicle(vehicleId, store.restore(vehicleId))) return false
        wheelCalibration = calibration
        calibrationStore = store
        verifiedVehicleId = vehicleId
        return true
    }

    @Synchronized
    fun acceptVerifiedMotion(motion: FtDrFusionEngine.Motion, emittedAtMs: Long): Boolean {
        // Identity is needed for persisted per-car calibration, NOT for live
        // verified wheel/yaw positioning. A car may be valid before pairing.
        if (!motion.wheelUnitsVerified || !motion.yawAxisAndUnitsVerified ||
            !motion.gearDirectionVerified) {
            wheelAcceleration.reset()
            wheelCalibration?.resetSegment()
            return false
        }

        val calibration = wheelCalibration
        val derivedAcceleration = wheelAcceleration.observe(
            monotonicMs = motion.monotonicMs,
            forwardWheelSpeedMps = motion.signedWheelSpeedMps,
            verified = !motion.reverse
        )
        if (derivedAcceleration != null) {
            calibration?.observeWheel(WheelSpeedAutoCalibration.WheelMotion(
                monotonicMs = motion.monotonicMs,
                forwardSpeedMps = motion.signedWheelSpeedMps,
                yawRateRadPerSecond = motion.yawRateRadiansPerSecond,
                longitudinalAccelerationMps2 = derivedAcceleration,
                verifiedWheelAndYawUnits = true,
                verifiedForwardGear = true
            ))
        } else {
            calibration?.resetSegment()
        }
        // Drive with independently verified uncalibrated speed until multiple
        // quality-gated GNSS/wheel segments establish a correction coefficient.
        val coefficientSpeed = calibration?.correctedMps(abs(motion.signedWheelSpeedMps))
        val adjusted = if (coefficientSpeed != null) motion.copy(
            signedWheelSpeedMps = if (motion.reverse) -coefficientSpeed
                else coefficientSpeed
        ) else motion
        return fusion.onMotion(adjusted, emittedAtMs)
    }

    @Synchronized
    fun acceptVerifiedGnss(fix: FtDrFusionEngine.Gnss): Boolean {
        // A physically verified GNSS fix is valid without prior wheel packets
        // or a per-car calibration identity. Only persistence is identity-gated.
        val accepted = fusion.onGnss(fix)
        if (!accepted) {
            wheelCalibration?.resetSegment()
            return false
        }
        if (fix.genuineCarFix && fix.accuracyVerified && fix.fixTimeVerified &&
            fix.source == GpsSourceMode.CAR) {
            val calibration = wheelCalibration
            if (calibration != null) {
                calibration.observeGnss(WheelSpeedAutoCalibration.IndependentGnss(
                    fixMonotonicMs = fix.fixMonotonicMs,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    horizontalAccuracyMetres = fix.horizontalAccuracyMetres,
                    independentCarGnssVerified = true,
                    monotonicFixEpochVerified = true
                ))
                // A segment completion is rare; do not perform synchronous
                // disk I/O per GNSS, wheel, or IMU frame.
                if (calibration.estimate().pendingPersistence) {
                    calibrationStore?.persistIfPending(
                        calibration, requireNotNull(verifiedVehicleId)
                    )
                }
            }
        }
        return true
    }

    @Synchronized
    fun reset() {
        fusion.reset()
        DrNavigationTelemetry.clear()
        verifiedVehicleId = null
        wheelCalibration = null
        calibrationStore = null
        wheelAcceleration.reset()
    }
}
