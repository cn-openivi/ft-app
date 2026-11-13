package app.ft.supercast.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingDeadlinePolicyTest {
    @Test fun discoveryTimeoutNeverStopsPairingOrConnecting() {
        // Use the enum type directly; do not instantiate the Android Service.

        assertTrue(PairingDeadlinePolicy.shouldExpire(WirelessPairingService.Phase.SEARCHING, false))
        assertTrue(PairingDeadlinePolicy.shouldExpire(WirelessPairingService.Phase.CODE_READY, false))
        assertTrue(PairingDeadlinePolicy.shouldExpire(WirelessPairingService.Phase.FAILED, false))
        assertFalse(PairingDeadlinePolicy.shouldExpire(WirelessPairingService.Phase.PAIRING, false))
        assertFalse(PairingDeadlinePolicy.shouldExpire(WirelessPairingService.Phase.CONNECTING, false))
        assertFalse(PairingDeadlinePolicy.shouldExpire(WirelessPairingService.Phase.READY, false))
        assertFalse(PairingDeadlinePolicy.shouldExpire(WirelessPairingService.Phase.CODE_READY, true))
    }
}
