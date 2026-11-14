package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

/** Owner's source-only GPS/DR test inputs, NOT vehicle certification. */
class IccoaDrInputBridgeTest {
    @Test fun rawIccoaCannotAdmitGpsOrWheelWithoutIndependentP0Proof() {
        IccoaDrInputBridge.beginSession(10)
        IccoaDrInputBridge.bindAuthenticatedReceiver("verified_car")
        IccoaDrInputBridge.rawGps(10, 1_000L)
        IccoaDrInputBridge.rawGear(38, 2, 1_050L)
        IccoaDrInputBridge.rawGyro(-1.0, 0.0, 0.0, 480_000_000_000L)
        assertEquals(1L, IccoaDrInputBridge.evidence().pairedSpeedSamples)
        assertEquals(1L, IccoaDrInputBridge.evidence().quantizedGyroFrames)
        assertFalse(IccoaDrInputBridge.submitQualifiedGnss(
            IccoaDrInputBridge.QualifiedGnss(10, "verified_car", 52.0, 13.0,
                0.0, 90.0, 1000L, 2000L, true, false, false, true)
        ))
        assertFalse(IccoaDrInputBridge.submitQualifiedMotion(
            IccoaDrInputBridge.QualifiedMotion(10, "verified_car", 2, 36.0,
                0.0, 1000L, 2000L, false, false, false, true)
        ))
    }

    @Test fun onlyStatisticallyPlausibleKmhScaleIsMarkedCandidateNotVerified() {
        IccoaDrInputBridge.beginSession(11)
        repeat(90) { i ->
            val gpsMps = 3L + (i % 15)
            IccoaDrInputBridge.rawGps(gpsMps, (i + 1) * 1_000L)
            IccoaDrInputBridge.rawGear((gpsMps * 3.6 + 1.5).toLong(),
                2L, (i + 1) * 1_000L + 100L)
        }
        val r = IccoaDrInputBridge.evidence()
        assertEquals(90L, r.pairedSpeedSamples)
        assertTrue(r.speedKmhHypothesisSupported)
        // Candidate recognition must NOT call FtDrRuntime.
    }

    @Test fun missingSourceEpochAndOtherVehicleNeverAdmitMotion() {
        IccoaDrInputBridge.beginSession(12)
        IccoaDrInputBridge.bindAuthenticatedReceiver("A")
        val sample = IccoaDrInputBridge.QualifiedMotion(
            12, "B", 2, 36.0, 0.1, 1_000L, 2_000L,
            true, true, true, true)
        assertFalse(IccoaDrInputBridge.submitQualifiedMotion(sample))
        IccoaDrInputBridge.endSession()
        assertFalse(IccoaDrInputBridge.submitQualifiedMotion(sample.copy(receiverId="A")))
    }

    @Test fun gyroFloorAndNonMonotonicClockAreRecordedWithoutFakeYaw() {
        IccoaDrInputBridge.beginSession(13)
        IccoaDrInputBridge.rawGyro(-1.0, 0.0, 0.0, 100L)
        IccoaDrInputBridge.rawGyro(-1.0, 0.0, 0.0, 95L)
        val x = IccoaDrInputBridge.evidence()
        assertEquals(2L, x.quantizedGyroFrames)
        assertEquals(1L, x.nonMonotonicGyroFrames)
    }
}
