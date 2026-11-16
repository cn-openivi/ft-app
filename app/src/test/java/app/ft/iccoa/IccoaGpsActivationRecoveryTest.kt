package app.ft.iccoa

import app.ft.core.NavigationInfo
import app.ft.core.ProtoWriter
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

    @Test fun decodedZeroZeroOemPacketDoesNotMaskMovingGpsBlackout() {
        val recovery = IccoaGpsActivationRecovery()
        recovery.beginSession()
        recovery.onGps(10_000L)
        val placeholderBytes = ProtoWriter()
            .fixed64(2, 0.0.toBits()).fixed64(3, 0.0.toBits()).toByteArray()
        val placeholder = IccoaGpsWire.decode(placeholderBytes)
        assertNotNull(placeholder)
        assertFalse(IccoaGpsWire.hasNavigationPosition(placeholder))
        for (t in 11_000L..23_000L step 1_000L) {
            if (t == 15_000L && IccoaGpsWire.hasNavigationPosition(placeholder)) {
                recovery.onGps(t) // same condition as ICCOA production callback
            }
            val trigger = recovery.onGear(35L, t, true)
            assertEquals("unexpected CONTROL11 at $t", t == 20_000L, trigger != null)
        }
        assertEquals(1, recovery.evidence().attempts)
        val genuine = IccoaGpsWire.decode(ProtoWriter()
            .fixed64(2, 55.7.toBits()).fixed64(3, 37.6.toBits()).toByteArray())
        assertTrue(IccoaGpsWire.hasNavigationPosition(genuine))
        recovery.onGps(24_000L)
        assertEquals(1, recovery.evidence().gpsReceiptsAfterAttempt)
    }

    @Test fun nextRealCarGpsPacketClosesGapAndPermitsOneLaterRearm() {
        val recovery = IccoaGpsActivationRecovery()
        recovery.beginSession()
        recovery.onGps(5_000L)
        for (t in 7_000L..15_000L step 1_000L) {
            val event = recovery.onGear(30L, t, true)
            assertEquals(t == 15_000L, event != null)
        }
        recovery.onGps(16_000L)
        assertEquals(1, recovery.evidence().gpsReceiptsAfterAttempt)
        for (t in 17_000L..26_000L step 1_000L) {
            val event = recovery.onGear(30L, t, true)
            assertEquals(t == 26_000L, event != null)
        }
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

    @Test fun queuedControlAndRealGpsResumeAreRecordedSeparatelyFromHuAck() {
        val recovery = IccoaGpsActivationRecovery()
        recovery.beginSession()
        recovery.onGps(10_000L)
        assertFalse(recovery.controlQueueResult(true)) // no attempt yet
        for (t in 11_000L..20_000L step 1_000L) {
            val triggered = recovery.onGear(40L, t, true)
            assertEquals(t == 20_000L, triggered != null)
        }
        assertTrue(recovery.evidence().controlOutcomePending)
        assertTrue(recovery.controlQueueResult(true))
        assertFalse(recovery.controlQueueResult(true)) // cannot count the same send twice
        var evidence = recovery.evidence()
        assertEquals(1, evidence.attempts)
        assertEquals(1, evidence.controlQueueSucceeded)
        assertEquals(0, evidence.controlQueueFailed)
        assertEquals(0, evidence.gpsReceiptsAfterAttempt)
        assertEquals(0, evidence.gpsReceiptsAfterQueuedControl)
        assertTrue(evidence.reportLine().contains("controlQueueSuccessIsNotHuAck=true"))

        recovery.onGps(21_000L)
        evidence = recovery.evidence()
        assertEquals(1, evidence.gpsReceiptsAfterAttempt)
        assertEquals(1, evidence.gpsReceiptsAfterQueuedControl)
        assertTrue(evidence.reportLine().contains(
            "gpsResumeAfterQueueIsTemporalCorrelationNotCausation=true"))
    }

    @Test fun failedQueueIsNotSuccessfulHuRecoveryAndNeverTriggersHiddenRetries() {
        val recovery = IccoaGpsActivationRecovery()
        recovery.beginSession()
        recovery.onGps(10_000L)
        for (t in 11_000L..20_000L step 1_000L) {
            val triggered = recovery.onGear(12L, t, true)
            assertEquals(t == 20_000L, triggered != null)
        }
        assertTrue(recovery.controlQueueResult(false))
        assertFalse(recovery.controlQueueResult(false))
        for (t in 21_000L..73_000L step 1_000L) {
            assertNull(recovery.onGear(12L, t, true))
        }
        assertEquals(1, recovery.evidence().attempts)
        assertEquals(0, recovery.evidence().controlQueueSucceeded)
        assertEquals(1, recovery.evidence().controlQueueFailed)
        recovery.onGps(74_000L) // unrelated resumption, no causal assertion
        assertEquals(1, recovery.evidence().gpsReceiptsAfterAttempt)
        assertEquals(0, recovery.evidence().gpsReceiptsAfterQueuedControl)
        recovery.endSession()
        assertFalse(recovery.controlQueueResult(true))
        recovery.beginSession()
        assertEquals(0, recovery.evidence().controlQueueFailed)
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
