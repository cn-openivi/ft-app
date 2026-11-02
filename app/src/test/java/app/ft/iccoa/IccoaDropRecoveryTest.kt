package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaDropRecoveryTest {
    @Test fun authenticatedUnexpectedDropAllowsOneAutomaticRecovery() {
        val guard = IccoaDropRecovery()
        assertTrue(guard.onUnexpectedDrop(wasConnected = true, autoConnectEnabled = true))
        assertFalse(guard.onUnexpectedDrop(wasConnected = true, autoConnectEnabled = true))
        assertFalse(guard.onUnexpectedDrop(wasConnected = false, autoConnectEnabled = true))
    }

    @Test fun failedPairingOrAuthenticationNeverTriggersAutonomousRetry() {
        val guard = IccoaDropRecovery()
        assertFalse(guard.onUnexpectedDrop(wasConnected = false, autoConnectEnabled = true))
        assertFalse(guard.onUnexpectedDrop(wasConnected = false, autoConnectEnabled = true))
        // The failed bootstrap did not consume an authenticated-link recovery.
        assertTrue(guard.onUnexpectedDrop(wasConnected = true, autoConnectEnabled = true))
    }

    @Test fun disablingAutoconnectKeepsUnexpectedDropManual() {
        val guard = IccoaDropRecovery()
        assertFalse(guard.onUnexpectedDrop(wasConnected = true, autoConnectEnabled = false))
        assertTrue(guard.onUnexpectedDrop(wasConnected = true, autoConnectEnabled = true))
    }

    @Test fun explicitUserConnectOrScanResetsBoundedBudget() {
        val guard = IccoaDropRecovery()
        assertTrue(guard.onUnexpectedDrop(wasConnected = true, autoConnectEnabled = true))
        assertFalse(guard.onUnexpectedDrop(wasConnected = true, autoConnectEnabled = true))
        guard.onExplicitOwnerRequest()
        assertTrue(guard.onUnexpectedDrop(wasConnected = true, autoConnectEnabled = true))
        assertFalse(guard.onUnexpectedDrop(wasConnected = true, autoConnectEnabled = true))
    }
}
