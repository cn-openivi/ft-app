package app.ft.core

import org.junit.Assert.*
import org.junit.Test

/** Synthetic, already-verified measurements; does not prove XA60 OEM units. */
class WheelSpeedAutoCalibrationTest {
    private fun drive(
        calibration: WheelSpeedAutoCalibration,
        startMs: Long,
        ratio: Double = 1.00333,
        jumpSecond: Int? = null,
        turnSecond: Int? = null,
        verifiedWheel: Boolean = true,
        verifiedGnssTime: Boolean = true,
        accuracy: Double = 1.0
    ) {
        for (step in 0..600) {
            val time = startMs + step * 100L
            val second = step / 10
            val yaw = if (second == turnSecond) 0.2 else 0.0
            calibration.observeWheel(WheelSpeedAutoCalibration.WheelMotion(
                monotonicMs = time, forwardSpeedMps = 20.0,
                yawRateRadPerSecond = yaw, longitudinalAccelerationMps2 = 0.0,
                verifiedWheelAndYawUnits = verifiedWheel,
                verifiedForwardGear = true
            ))
            if (step % 10 == 0) {
                val travelled = step * 0.1 * 20.0 * ratio +
                    (if (second == jumpSecond) 50.0 else 0.0)
                calibration.observeGnss(WheelSpeedAutoCalibration.IndependentGnss(
                    fixMonotonicMs = time,
                    latitude = 55.0 + travelled / 111195.080233,
                    longitude = 37.0,
                    horizontalAccuracyMetres = accuracy,
                    independentCarGnssVerified = true,
                    monotonicFixEpochVerified = verifiedGnssTime
                ))
            }
        }
    }

    @Test fun twoMillimetreDiameterClassDifferenceLearnsOnlyAfterSeveralLongStraights() {
        val c = WheelSpeedAutoCalibration()
        assertTrue(c.bindVehicle("proven-car-a"))
        assertNull(c.correctedMps(20.0))
        drive(c, 100_000L)
        assertEquals(1, c.estimate().acceptedSegments)
        assertFalse(c.estimate().confident)
        drive(c, 200_000L)
        drive(c, 300_000L)
        assertEquals(3, c.estimate().acceptedSegments)
        assertTrue(c.estimate().confident)
        assertTrue(c.estimate().multiplier > 1.0)
        assertTrue(c.estimate().multiplier < 1.00333)
        assertTrue(c.correctedMps(20.0)!! > 20.0)
    }

    @Test fun restartRestoresOneCoefficientOnlyToSameVerifiedVehicle() {
        val c = WheelSpeedAutoCalibration()
        assertFalse(c.bindVehicle(""))
        assertTrue(c.bindVehicle("proven-car-a"))
        repeat(3) { drive(c, (it + 1) * 100_000L) }
        val snap = c.snapshotForPersistence()!!
        assertTrue(c.estimate().pendingPersistence)
        assertFalse(c.confirmPersisted(snap.copy(vehicleId = "other-car")))
        assertTrue(c.confirmPersisted(snap))
        assertNull(c.snapshotForPersistence())

        val next = WheelSpeedAutoCalibration()
        assertTrue(next.bindVehicle("proven-car-a", snap))
        assertEquals(snap.multiplier, next.estimate().multiplier, 0.0)
        assertTrue(next.estimate().confident)
        assertTrue(next.bindVehicle("proven-car-b", snap))
        assertEquals(1.0, next.estimate().multiplier, 0.0)
        assertFalse(next.estimate().confident)
    }

    @Test fun multipathJumpTurnAndBadAccuracyCannotTrainOrTeleportCoefficient() {
        val c = WheelSpeedAutoCalibration()
        c.bindVehicle("proven-car")
        drive(c, 100_000L, jumpSecond = 30)
        drive(c, 200_000L, turnSecond = 30)
        drive(c, 300_000L, accuracy = 5.0)
        assertEquals(0, c.estimate().acceptedSegments)
        assertEquals(1.0, c.estimate().multiplier, 0.0)
        assertNull(c.snapshotForPersistence())
    }

    @Test fun unknownWheelUnitAndUnprovenGnssFixEpochAlwaysReject() {
        val c = WheelSpeedAutoCalibration()
        c.bindVehicle("proven-car")
        drive(c, 100_000L, verifiedWheel = false)
        drive(c, 200_000L, verifiedGnssTime = false)
        assertEquals(0, c.estimate().acceptedSegments)
        assertFalse(c.estimate().confident)
    }

    @Test fun gnssOutageAndBadChronologyCannotChangeLearnedCoefficient() {
        val c = WheelSpeedAutoCalibration()
        c.bindVehicle("proven-car")
        repeat(3) { drive(c, (it + 1) * 100_000L) }
        val multiplier = c.estimate().multiplier
        c.observeGnss(WheelSpeedAutoCalibration.IndependentGnss(
            fixMonotonicMs = 400_000L, latitude = 55.0, longitude = 37.0,
            horizontalAccuracyMetres = 1.0,
            independentCarGnssVerified = false,
            monotonicFixEpochVerified = false
        ))
        c.observeWheel(WheelSpeedAutoCalibration.WheelMotion(
            monotonicMs = 400_000L, forwardSpeedMps = 20.0,
            yawRateRadPerSecond = 0.0, longitudinalAccelerationMps2 = 0.0,
            verifiedWheelAndYawUnits = false, verifiedForwardGear = true
        ))
        assertEquals(multiplier, c.estimate().multiplier, 0.0)
        assertEquals(3, c.estimate().acceptedSegments)
        assertTrue(c.estimate().confident)
    }
}
