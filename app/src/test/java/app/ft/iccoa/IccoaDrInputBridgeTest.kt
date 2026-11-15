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

    @Test fun authenticatedGearKmhIntegratesDistanceAcross64SecondGpsBlackoutWithoutYaw() {
        IccoaDrInputBridge.beginSession(188_001L)
        IccoaDrInputBridge.bindAuthenticatedReceiver("FAW_RAV4_XA60")
        // 9 km/h == 2.5 metres/second. Exactly 64 monotonic 1s gaps.
        IccoaDrInputBridge.rawGear(9L, 2L, 1_000L)
        for (i in 1..64) IccoaDrInputBridge.rawGear(9L, 2L, 1_000L + i * 1_000L)
        val evidence = IccoaDrInputBridge.evidence()
        assertEquals(160.0, evidence.gearDistanceMetres, 1e-9)
        assertEquals(64L, evidence.gearSpeedIntervalsIntegrated)
        assertEquals(65L, evidence.gearSpeedFramesAccepted)
        assertEquals(0L, evidence.gearSpeedGapMsUncovered)
        assertEquals(0L, evidence.pairedSpeedSamples) // no OEM GPS at all
        assertEquals(0L, evidence.quantizedGyroFrames) // no yaw needed
        assertTrue(evidence.reportLine().contains("gearSpeedUnit=KMH_FAWRAV4_FIELD_CONFIRMED"))
        assertTrue(evidence.reportLine().contains("gearDistanceQuality=ESTIMATE_NOT_INDEPENDENT_WHEEL_ODOMETER_NO_HEADING"))
    }

    @Test fun missingGearSamplesNeverInventDistanceDuringLongBlackout() {
        IccoaDrInputBridge.beginSession(188_002L)
        IccoaDrInputBridge.bindAuthenticatedReceiver("FAW_RAV4_XA60")
        IccoaDrInputBridge.rawGear(36L, 2L, 1_000L)
        IccoaDrInputBridge.rawGear(36L, 2L, 61_000L) // 60s with NO speed receipt: unknown distance
        IccoaDrInputBridge.rawGear(0L, 2L, 62_000L)  // 1s deceleration: 5 metres
        val evidence = IccoaDrInputBridge.evidence()
        assertEquals(5.0, evidence.gearDistanceMetres, 1e-9)
        assertEquals(1L, evidence.gearSpeedIntervalsIntegrated)
        assertEquals(60_000L, evidence.gearSpeedGapMsUncovered)
    }

    @Test fun distanceDropsNonMonotonicGearAndFreezesOnDisconnect() {
        IccoaDrInputBridge.beginSession(188_003L)
        IccoaDrInputBridge.bindAuthenticatedReceiver("FAW_RAV4_XA60")
        IccoaDrInputBridge.rawGear(36L, 2L, 1_000L)
        IccoaDrInputBridge.rawGear(100L, 2L, 1_000L) // duplicate monotonic time, ignore
        IccoaDrInputBridge.rawGear(100L, 2L, 900L) // time backwards, ignore
        IccoaDrInputBridge.rawGear(36L, 2L, 2_000L)
        assertEquals(10.0, IccoaDrInputBridge.evidence().gearDistanceMetres, 1e-9)
        assertEquals(2L, IccoaDrInputBridge.evidence().gearSpeedNonMonotonicFrames)
        IccoaDrInputBridge.endSession()
        IccoaDrInputBridge.rawGear(36L, 2L, 3_000L)
        assertEquals(10.0, IccoaDrInputBridge.evidence().gearDistanceMetres, 1e-9)
        IccoaDrInputBridge.beginSession(188_004L)
        assertEquals(0.0, IccoaDrInputBridge.evidence().gearDistanceMetres, 0.0)
    }

    @Test fun distanceIsNotIntegratedBeforeAuthenticationOrAcrossIdentityRebind() {
        IccoaDrInputBridge.beginSession(188_005L)
        IccoaDrInputBridge.rawGear(36L, 2L, 1_000L)
        IccoaDrInputBridge.rawGear(36L, 2L, 2_000L)
        assertEquals(0.0, IccoaDrInputBridge.evidence().gearDistanceMetres, 0.0)
        IccoaDrInputBridge.bindAuthenticatedReceiver("old-car")
        IccoaDrInputBridge.rawGear(36L, 2L, 3_000L)
        IccoaDrInputBridge.rawGear(36L, 2L, 4_000L)
        assertEquals(10.0, IccoaDrInputBridge.evidence().gearDistanceMetres, 1e-9)
        IccoaDrInputBridge.bindAuthenticatedReceiver("new-car")
        IccoaDrInputBridge.rawGear(36L, 2L, 5_000L)
        assertEquals(10.0, IccoaDrInputBridge.evidence().gearDistanceMetres, 1e-9)
        IccoaDrInputBridge.rawGear(36L, 2L, 6_000L)
        assertEquals(20.0, IccoaDrInputBridge.evidence().gearDistanceMetres, 1e-9)
    }
}
