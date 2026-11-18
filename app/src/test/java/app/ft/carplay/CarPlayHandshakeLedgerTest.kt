package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayHandshakeLedgerTest {
    @Test fun completePhysicalEvidenceSequenceIsTheOnlyActivePath() {
        val gate = CarPlayHandshakeLedger()
        assertTrue(gate.observeBluetooth(existingBond = true, sdpObserved = true, nowMs = 10))
        assertEquals(CarPlayHandshakeLedger.Phase.BT_DISCOVERED, gate.status.phase)
        assertTrue(gate.startIap2(verifiedProfile = true, nowMs = 20))
        assertTrue(gate.iap2Established(realSessionAndAuth = true, nowMs = 30))
        assertTrue(gate.wifiEstablished(realHandover = true, nowMs = 40))
        assertTrue(gate.confirmAirPlay(
            setupSucceeded = true, huReportsActive = true,
            visibleFrames = true, reverseTouch = true, nowMs = 50
        ))
        assertEquals(CarPlayHandshakeLedger.Phase.ACTIVE, gate.status.phase)
        assertTrue(gate.status.observedFrames && gate.status.observedTouch)
        gate.reset(60)
        assertEquals(CarPlayHandshakeLedger.Phase.UNAVAILABLE, gate.status.phase)
        assertFalse(gate.status.observedFrames)
    }

    @Test fun sdpAndBondAloneNeverMeanCarPlay() {
        val gate = CarPlayHandshakeLedger()
        assertTrue(gate.observeBluetooth(true, true, 1))
        assertEquals(CarPlayHandshakeLedger.Phase.BT_DISCOVERED, gate.status.phase)
        assertFalse(gate.startIap2(verifiedProfile = false, nowMs = 2))
        assertEquals(CarPlayHandshakeLedger.Phase.BLOCKED, gate.status.phase)
        assertEquals(CarPlayHandshakeLedger.Reason.SDP_NOT_VERIFIED, gate.status.reason)
    }

    @Test fun missingPhysicalBondFailsBeforeAnyProtocolAttempt() {
        val gate = CarPlayHandshakeLedger()
        assertFalse(gate.observeBluetooth(false, true, 1))
        assertEquals(CarPlayHandshakeLedger.Reason.NO_EXISTING_BOND, gate.status.reason)
    }

    @Test fun wirelessOrAuthenticationFailuresCannotBePromoted() {
        val auth = CarPlayHandshakeLedger()
        assertTrue(auth.observeBluetooth(true, true, 0))
        assertTrue(auth.startIap2(true, 1))
        assertFalse(auth.iap2Established(false, 2))
        assertEquals(CarPlayHandshakeLedger.Reason.IAP2_AUTH_FAILED, auth.status.reason)
        assertFalse(auth.wifiEstablished(true, 3))
        assertEquals(CarPlayHandshakeLedger.Phase.BLOCKED, auth.status.phase)

        val wifi = CarPlayHandshakeLedger()
        assertTrue(wifi.observeBluetooth(true, true, 0))
        assertTrue(wifi.startIap2(true, 1))
        assertTrue(wifi.iap2Established(true, 2))
        assertFalse(wifi.wifiEstablished(false, 3))
        assertEquals(CarPlayHandshakeLedger.Reason.WIFI_HANDOVER_FAILED, wifi.status.reason)
    }

    @Test fun noDisplayedFramesOrReverseTouchMeansNoActive() {
        val gate = CarPlayHandshakeLedger()
        assertTrue(gate.observeBluetooth(true, true, 0))
        assertTrue(gate.startIap2(true, 1))
        assertTrue(gate.iap2Established(true, 2))
        assertTrue(gate.wifiEstablished(true, 3))
        assertFalse(gate.confirmAirPlay(true, true, true, false, 4))
        assertEquals(CarPlayHandshakeLedger.Reason.HU_VIDEO_OR_TOUCH_NOT_CONFIRMED, gate.status.reason)
    }

    @Test fun stalledStagesTimeOutWithoutFakeSuccess() {
        val gate = CarPlayHandshakeLedger(phaseTimeoutMs = 100)
        assertTrue(gate.observeBluetooth(true, true, 0))
        assertFalse(gate.checkTimeout(100))
        assertTrue(gate.checkTimeout(101))
        assertEquals(CarPlayHandshakeLedger.Reason.PHASE_TIMEOUT, gate.status.reason)
        assertEquals(CarPlayHandshakeLedger.Phase.BLOCKED, gate.status.phase)
    }

    @Test fun outOfOrderEventsAreRejected() {
        val gate = CarPlayHandshakeLedger()
        assertFalse(gate.confirmAirPlay(true, true, true, true, 4))
        assertEquals(CarPlayHandshakeLedger.Reason.OUT_OF_ORDER, gate.status.reason)
    }
}
