package app.ft.iccoa

import app.ft.core.NavigationInfo
import org.junit.Assert.*
import org.junit.Test

/** One-shot OEM Control11 reassertion; never synthetic AA position or phone fallback. */
class IccoaGpsActivationRecoveryTest {
    @Test fun fullParkingLikeGpsBlackoutWithMovingGearRearmsOnceOnly() {
        val recovery = IccoaGpsActivationRecovery()
        recovery.beginSession()
        recovery.onGps(10_000L)
        for (at in 11_000L..73_000L step 1_000L) {
            val trigger = recovery.onGear(40L, at, true)
            if (at == 20_000L) {
                assertNotNull(trigger)
                assertEquals(IccoaGpsActivationRecovery.Trigger.Kind.GPS_RECEIPT_STOPPED,
                    trigger!!.kind)
                assertEquals(10_000L, trigger.elapsedMs)
            } else assertNull("duplicate at $at", trigger)
        }
        val evidence = recovery.evidence()
        assertEquals(1, evidence.attempts)
        assertEquals(1, evidence.postFixAttempts)
        assertEquals(0, evidence.initialAttempts)
        assertEquals(0, evidence.gpsReceiptsAfterAttempt)
        assertTrue(evidence.reportLine().contains("CAR_GPS_PRIMARY_NO_PHONE_FALLBACK"))
        assertTrue(evidence.reportLine().contains("satellitesActuallyLost=UNKNOWN"))
    }

    @Test fun nextRealCarGpsPacketClosesGapAndPermitsOneLaterRearm() {
        val recovery = IccoaGpsActivationRecovery()
        recovery.beginSession()
        recovery.onGps(5_000L)
        assertNull(recovery.onGear(30L, 7_000L, true))
        assertNotNull(recovery.onGear(30L, 15_000L, true))
        recovery.onGps(16_000L)
        assertEquals(1, recovery.evidence().gpsReceiptsAfterAttempt)
        assertNull(recovery.onGear(30L, 17_000L, true))
        assertNotNull(recovery.onGear(30L, 26_000L, true))
        assertEquals(2, recovery.evidence().attempts)
    }

    @Test fun initialAcquisitionDelayCanRearmOnlyWithVerifiedDemandAndContinuousGear() {
        val recovery = IccoaGpsActivationRecovery()
        recovery.beginSession()
        assertNull(recovery.onGear(48L, 1_000L, false))
        assertNull(recovery.onGear(48L, 12_000L, false))
        assertNull(recovery.onGear(48L, 13_000L, true))
        assertNull(recovery.onGear(48L, 15_000L, true))
        // 8s gap since last gear reset continuity. No spurious re-arm.
        assertNull(recovery.onGear(48L, 23_000L, true))
    }

    @Test fun genuineContinuousFirstGpsWaitRearmsOnceThenReset() {
        val recovery = IccoaGpsActivationRecovery()
        recovery.beginSession()
        for (t in 1_000L..16_000L step 1_000L) {
            val event = recovery.onGear(20L, t, true)
            assertEquals(t == 11_000L, event != null)
            if (t == 11_000L) {
                assertEquals(IccoaGpsActivationRecovery.Trigger.Kind.FIRST_GPS_NOT_RECEIVED,
                    event!!.kind)
            }
        }
        assertEquals(1, recovery.evidence().initialAttempts)
        recovery.endSession()
        assertNull(recovery.onGear(20L, 45_000L, true))
        assertTrue(recovery.evidence().frozen)
        recovery.beginSession()
        assertEquals(0, recovery.evidence().attempts)
    }

    @Test fun parkedMalformedReversedAndSparseGearDoNotSendControl() {
        val recovery = IccoaGpsActivationRecovery()
        recovery.beginSession()
        recovery.onGps(1_000L)
        assertNull(recovery.onGear(0L, 2_000L, true))
        assertNull(recovery.onGear(-1L, 3_000L, true))
        assertNull(recovery.onGear(235L, 4_000L, true))
        assertNull(recovery.onGear(5L, 5_000L, true))
        assertNull(recovery.onGear(5L, 25_000L, true))
        assertNull(recovery.onGear(5L, 24_000L, true))
        assertNull(recovery.onGear(5L, 26_000L, false))
        assertEquals(0, recovery.evidence().attempts)
    }

    @Test fun rearmUsesExactlyTheExistingOemNavigationWireSchema() {
        val requested = IccoaNavigation.sensorActivation(NavigationInfo(), aaConnected = true)
        assertEquals(11, IccoaNavigation.CONTROL_METHOD)
        assertArrayEquals(
            byteArrayOf(0x08, 0x01, 0x1a, 0x00, 0x22, 0x01, 0x6d, 0x32, 0x00),
            IccoaNavigation.encode(requested))
        assertFalse(IccoaNavigation.sensorActivation(
            NavigationInfo(), aaConnected = false).active)
    }
}
