package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaDropRecoveryTest {
    private fun lost(guard: IccoaDropRecovery, connected: Boolean = false, transport: Boolean = true) =
        guard.onFailure(connected, autoConnectEnabled = true, retryableTransportFailure = transport)

    @Test fun initialRememberedCarAutoConnectAlsoGetsThreeTransportAttempts() {
        val guard = IccoaDropRecovery()
        assertEquals(1, guard.onInitialRememberedCarAutoSelect())
        assertEquals(2, lost(guard))
        assertEquals(2, guard.onInitialRememberedCarAutoSelect())
        assertEquals(3, lost(guard))
        assertNull(lost(guard))
        assertFalse(guard.active)
    }

    @Test fun initialAutoConnectStillStopsOnPinPromptAndAuthRejection() {
        val prompt = IccoaDropRecovery()
        assertEquals(1, prompt.onInitialRememberedCarAutoSelect())
        prompt.onPinRequired()
        assertNull(lost(prompt))
        val rejected = IccoaDropRecovery()
        assertEquals(1, rejected.onInitialRememberedCarAutoSelect())
        assertNull(lost(rejected, transport = false))
        assertFalse(rejected.active)
    }

    @Test fun cachedPinGattQueueFailureCanRetryTransportButNeverFourthTime() {
        val guard = IccoaDropRecovery()
        // Initial remembered-car connection counted before the cached PIN is sent.
        assertEquals(1, guard.onInitialRememberedCarAutoSelect())
        // A local GATT queue failure or confirmed ATT write failure cannot
        // imply credential rejection; reconnect may retry after rediscovery.
        assertEquals(2, lost(guard, transport = true))
        assertEquals(3, lost(guard, transport = true))
        assertNull(lost(guard, transport = true))
        assertFalse(guard.active)
    }

    @Test fun ambiguousSavedPinSilenceOrRejectionRequiresManualInput() {
        val guard = IccoaDropRecovery()
        assertEquals(1, guard.onInitialRememberedCarAutoSelect())
        assertEquals(2, lost(guard, transport = true))
        // Silence after a transmitted PIN may be OEM rejection, so it is
        // intentionally NOT a typed transport failure and prompts the owner.
        assertNull(lost(guard, transport = false))
        assertFalse(guard.active)
        assertNull(lost(guard, transport = true))
    }

    @Test fun firstEstablishedLossStartsAttemptOneOfThree() {
        val guard = IccoaDropRecovery()
        assertEquals(3, IccoaDropRecovery.MAX_ATTEMPTS)
        assertNull(lost(guard)) // No prior established session.
        assertEquals(1, lost(guard, connected = true))
        assertTrue(guard.active)
    }

    @Test fun twoMoreTransportFailuresConsumeOnlyTwoRemainingSlots() {
        val guard = IccoaDropRecovery()
        assertEquals(1, lost(guard, connected = true))
        assertEquals(2, lost(guard))
        assertEquals(3, lost(guard))
        assertNull(lost(guard)) // Strictly never a fourth reconnect.
        assertFalse(guard.active)
        assertNull(lost(guard)) // Exhausted episode stays stopped.
    }

    @Test fun authenticationOrPinFailureStopsRecoveryWithoutRetry() {
        val guard = IccoaDropRecovery()
        assertEquals(1, lost(guard, connected = true))
        assertNull(lost(guard, transport = false))
        assertFalse(guard.active)
        assertNull(lost(guard))
    }

    @Test fun explicitPinPromptCancelsRemainingAttempts() {
        val guard = IccoaDropRecovery()
        assertEquals(1, lost(guard, connected = true))
        guard.onPinRequired()
        assertFalse(guard.active)
        assertNull(lost(guard))
    }

    @Test fun disabledAutoConnectCannotStartNorContinueRecovery() {
        val guard = IccoaDropRecovery()
        assertNull(guard.onFailure(true, false, true))
        assertFalse(guard.active)
        assertEquals(1, lost(guard, connected = true))
        assertNull(guard.onFailure(false, false, true))
        assertNull(lost(guard))
    }

    @Test fun successfulReconnectEndsEpisodeAndNextRealDropStartsFreshBudget() {
        val guard = IccoaDropRecovery()
        assertEquals(1, lost(guard, connected = true))
        assertEquals(2, lost(guard))
        guard.onConnected()
        assertNull(lost(guard)) // New failure before a connection does not restart.
        assertEquals(1, lost(guard, connected = true))
        assertEquals(2, lost(guard))
    }

    @Test fun manuallySelectedCarOrScanEndsOldRecoveryEpisode() {
        val guard = IccoaDropRecovery()
        assertEquals(1, lost(guard, connected = true))
        guard.onExplicitOwnerRequest()
        assertFalse(guard.active)
        assertNull(lost(guard))
        assertEquals(1, lost(guard, connected = true))
    }

    @Test fun failureNotClassifiedAsTransportNeverInitiatesAutoReconnect() {
        val guard = IccoaDropRecovery()
        assertNull(lost(guard, connected = true, transport = false))
        assertFalse(guard.active)
        assertNull(lost(guard))
    }
    @Test fun oemControlMethod23IsConsumedInsideSessionAsTransportDisconnect() {
        assertTrue(IccoaCloseEvent.isRemoteDisconnect(1, 23))
        assertFalse(IccoaCloseEvent.isRemoteDisconnect(1, 22))
        assertFalse(IccoaCloseEvent.isRemoteDisconnect(5, 23))
    }

    @Test fun typedCloseContractNeverConfusesQuickPinWithTransportRetry() {
        assertEquals(IccoaCloseAction.REQUIRE_NORMAL_PIN,
            IccoaCloseEvent.classify("quick rejected", quickNormalFallback = true).action)
        assertEquals(IccoaCloseAction.RETRY_TRANSPORT,
            IccoaCloseEvent.classify("car disconnected", transportFailure = true).action)
        assertEquals(IccoaCloseAction.STOP,
            IccoaCloseEvent.classify("bad PIN").action)
    }

    @Test fun controlBackpressureAndSocketWriteTimeoutAreBoundedTransportLosses() {
        // IccoaSession previously called fail() without transportFailure=true
        // for these two concrete I/O breakdowns, so the typed event STOPped
        // a connected car without using the already approved 3-try recovery.
        for (reason in listOf(
            "Переполнена очередь управления ICCOA",
            "Тайм-аут отправки данных ICCOA"
        )) {
            val typed = IccoaCloseEvent.classify(reason, transportFailure = true)
            assertEquals(IccoaCloseAction.RETRY_TRANSPORT, typed.action)
            val guard = IccoaDropRecovery()
            assertEquals(1, guard.onFailure(true, true,
                typed.action == IccoaCloseAction.RETRY_TRANSPORT))
            assertEquals(2, guard.onFailure(false, true,
                typed.action == IccoaCloseAction.RETRY_TRANSPORT))
            assertEquals(3, guard.onFailure(false, true,
                typed.action == IccoaCloseAction.RETRY_TRANSPORT))
            assertNull(guard.onFailure(false, true,
                typed.action == IccoaCloseAction.RETRY_TRANSPORT))
            assertFalse(guard.active)
            // User-disabled reconnect or PIN/Quick auth rejection is NOT
            // an interchangeable socket-fault retry.
            assertNull(IccoaDropRecovery().onFailure(true, false, true))
            assertEquals(IccoaCloseAction.REQUIRE_NORMAL_PIN,
                IccoaCloseEvent.classify("OEM Quick rejected",
                    quickNormalFallback = true).action)
        }
    }

    @Test fun postAuthControlLaneTimeoutRetriesOnlyThreeTransportAttempts() {
        // A completed signed AuthConfirm followed by no CONTROL lane within
        // 25 seconds is a missing TCP transport lane, not a bad PIN.
        val timeout = IccoaCloseEvent.classify(
            "Автомобиль не открыл канал управления ICCOA", transportFailure = true)
        assertEquals(IccoaCloseAction.RETRY_TRANSPORT, timeout.action)
        val guard = IccoaDropRecovery()
        assertEquals(1, guard.onFailure(
            connected = true, autoConnectEnabled = true,
            retryableTransportFailure = timeout.action == IccoaCloseAction.RETRY_TRANSPORT))
        assertEquals(2, guard.onFailure(false, true, true))
        assertEquals(3, guard.onFailure(false, true, true))
        assertNull(guard.onFailure(false, true, true))
        assertFalse(guard.active)

        // Auth rejection must never be reclassified as transport timeout.
        val authRejected = IccoaCloseEvent.classify("PIN rejected")
        assertEquals(IccoaCloseAction.STOP, authRejected.action)
        assertNull(IccoaDropRecovery().onFailure(
            connected = true, autoConnectEnabled = true,
            retryableTransportFailure = authRejected.action == IccoaCloseAction.RETRY_TRANSPORT))
        assertEquals(IccoaCloseAction.REQUIRE_NORMAL_PIN,
            IccoaCloseEvent.classify("QUICK rejected", quickNormalFallback = true).action)
    }

    @Test fun typedCloseContractRejectsConflictingActions() {
        try {
            IccoaCloseEvent.classify("invalid", transportFailure = true, quickNormalFallback = true)
            fail("Conflicting recovery and PIN fallback should fail closed")
        } catch (_: IllegalArgumentException) {
            // Expected: never dispatch two incompatible terminal actions.
        }
    }

}
