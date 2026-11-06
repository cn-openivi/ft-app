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
        // Post-auth, pre-CONTROL is not yet connected. A remembered-car
        // auto-select already owns attempt #1; it may retry only #2 and #3.
        val guard = IccoaDropRecovery()
        assertEquals(1, guard.onInitialRememberedCarAutoSelect())
        assertEquals(2, guard.onFailure(
            wasConnected = false, autoConnectEnabled = true,
            retryableTransportFailure = timeout.action == IccoaCloseAction.RETRY_TRANSPORT))
        assertEquals(3, guard.onFailure(false, true, true))
        assertNull(guard.onFailure(false, true, true))
        assertFalse(guard.active)
        // An owner-initiated first connection has no recovery episode.
        assertNull(IccoaDropRecovery().onFailure(false, true, true))

        // Auth rejection must never be reclassified as transport timeout.
        val authRejected = IccoaCloseEvent.classify("PIN rejected")
        assertEquals(IccoaCloseAction.STOP, authRejected.action)
        assertNull(IccoaDropRecovery().onFailure(
            wasConnected = true, autoConnectEnabled = true,
            retryableTransportFailure = authRejected.action == IccoaCloseAction.RETRY_TRANSPORT))
        assertEquals(IccoaCloseAction.REQUIRE_NORMAL_PIN,
            IccoaCloseEvent.classify("QUICK rejected", quickNormalFallback = true).action)
    }

    @Test fun replacementNetworkWithoutGatewayOwnsItsRetryAfterStaleTimerFires() {
        val selected = IccoaNetworkCallbackGate<String>()
        val retry = IccoaGatewayRetryGate<String>()
        val checked = mutableListOf<String>()

        // First Network A is connected but has no usable IPv4 gateway.
        selected.onAvailable("A")
        assertTrue(retry.schedule("A"))
        assertFalse(retry.schedule("A")) // no duplicate timer for A

        // Android replaces A with B, whose gateway is also initially absent.
        selected.onAvailable("B")
        assertTrue(retry.schedule("B"))
        assertFalse(retry.schedule("B")) // only one B timer
        // A's 1-second timer runs after B was selected: it must not clear B.
        assertFalse(retry.consume("A"))
        assertTrue(selected.isCurrent("B"))

        // B's own callback is still scheduled and can check LinkProperties.
        assertTrue(retry.consume("B"))
        checked.add("B")
        assertEquals(listOf("B"), checked)

        // Gateway still absent at first B poll: allow a bounded repeated poll.
        assertTrue(retry.schedule("B"))
        assertFalse(retry.consume("A"))
        assertTrue(retry.consume("B"))
        checked.add("B")
        assertEquals(listOf("B", "B"), checked)

        // If B now exposes a gateway, pending work must be cancellable.
        assertTrue(retry.schedule("B"))
        retry.clear()
        assertFalse(retry.consume("B"))
        assertTrue(selected.isCurrent("B"))
    }

    @Test fun replacedNetworkCannotStartAuthOrTearDownLiveConnection() {
        val gate = IccoaNetworkCallbackGate<String>()
        gate.onAvailable("A")
        assertTrue(gate.isCurrent("A"))
        gate.onAvailable("B")
        // onLinkPropertiesChanged(A), delayed gateway retry(A), onLost(A)
        // must neither start authentication on A nor close the B session.
        assertFalse(gate.isCurrent("A"))
        assertFalse(gate.onLost("A"))
        assertTrue(gate.isCurrent("B"))
        assertTrue(gate.onLost("B"))
        assertFalse(gate.isCurrent("B"))
        gate.onAvailable("C")
        gate.clear()
        assertFalse(gate.isCurrent("C"))
    }

    @Test fun queuedAuthenticationCannotBindSupersededNetwork() {
        val gate = IccoaNetworkCallbackGate<String>()
        gate.onAvailable("A")
        // networkReady(A) queued authenticate(A), but the worker has not run.
        gate.onAvailable("B")
        val bound = mutableListOf<String>()
        assertFalse(gate.bindIfCurrent("A") { bound.add("A") })
        assertTrue(bound.isEmpty())
        // B may bind normally; a late onLost(A) cannot close B.
        assertFalse(gate.onLost("A"))
        assertTrue(gate.bindIfCurrent("B") { bound.add("B") })
        assertEquals(listOf("B"), bound)
        assertTrue(gate.onLost("B"))
        assertFalse(gate.bindIfCurrent("B") { bound.add("B again") })
        assertEquals(listOf("B"), bound)
    }

    @Test fun networkReplacementAfterTcpConnectCannotSendAuthOnStaleSocket() {
        val gate = IccoaNetworkCallbackGate<String>()
        val authRequests = mutableListOf<String>()
        val closed = mutableListOf<String>()
        gate.onAvailable("A")
        assertTrue(gate.bindIfCurrent("A") { /* TCP bind A */ })
        // Simulate the network switch during blocking socket.connect(A).
        gate.onAvailable("B")
        if (!gate.isCurrent("A")) closed.add("A") else authRequests.add("A")
        assertEquals(listOf("A"), closed)
        assertTrue(authRequests.isEmpty())
        assertTrue(gate.bindIfCurrent("B") { /* TCP bind B */ })
        if (!gate.isCurrent("B")) closed.add("B") else authRequests.add("B")
        assertEquals(listOf("B"), authRequests)
        // An old onLost(A) must not close the current B transport.
        assertFalse(gate.onLost("A"))
        assertTrue(gate.isCurrent("B"))
    }

    @Test fun lateAuthenticatedResponseCannotCommitCipherOnSupersededNetwork() {
        val gate = IccoaNetworkCallbackGate<String>()
        var cipher: String? = null
        val confirms = mutableListOf<String>()
        val ready = mutableListOf<String>()
        gate.onAvailable("A")
        // A has already sent AuthRequest. Android replaces its Network while
        // the authenticated response is still being decoded.
        gate.onAvailable("B")
        assertFalse(gate.commitIfCurrent("A") { cipher = "A-key" })
        if (gate.isCurrent("A")) ready.add("A")
        if (gate.isCurrent("A")) confirms.add("A")
        assertEquals(null, cipher)
        assertTrue(ready.isEmpty())
        assertTrue(confirms.isEmpty())
        // Current B can commit a verified key without A reviving itself.
        assertTrue(gate.commitIfCurrent("B") { cipher = "B-key" })
        if (gate.isCurrent("B")) ready.add("B")
        if (gate.isCurrent("B")) confirms.add("B")
        assertEquals("B-key", cipher)
        assertEquals(listOf("B"), ready)
        assertEquals(listOf("B"), confirms)
    }

    @Test fun staleNetworkAuthExceptionCannotTerminateReplacementAttempt() {
        val gate = IccoaNetworkCallbackGate<String>()
        gate.onAvailable("A")
        assertTrue(gate.isCurrent("A"))
        // A's TCP connect blocks while Android replaces its Network handle.
        gate.onAvailable("B")
        // A then throws SocketException. Its catch must not clear the PIN,
        // classify a terminal close, or kill the live B authentication.
        assertFalse(gate.isCurrent("A"))
        assertTrue(gate.isCurrent("B"))
        assertTrue(gate.bindIfCurrent("B") { assertTrue(gate.isCurrent("B")) })
        // A failure on the *current* Network still reaches normal fail-closed handling.
        assertTrue(gate.isCurrent("B"))
        assertEquals(IccoaCloseAction.STOP,
            IccoaCloseEvent.classify("PIN rejected").action)
    }

    @Test fun normalPinSurvivesNetworkReplacementButClearsAfterVerifiedHandshake() {
        val gate = IccoaNetworkCallbackGate<String>()
        gate.onAvailable("A")
        var pendingPin: String? = "123456"
        pendingPin = IccoaNetworkPinLifecycle.afterAuthQueued(pendingPin)
        assertEquals("123456", pendingPin)
        // Network A becomes stale before its queued TCP bind; B must retain
        // the same attempt's credential, not prompt for a new PIN.
        gate.onAvailable("B")
        assertFalse(gate.bindIfCurrent("A") { fail("stale auth") })
        assertEquals("123456", pendingPin)
        assertTrue(gate.bindIfCurrent("B") { assertEquals("123456", pendingPin) })
        pendingPin = IccoaNetworkPinLifecycle.afterVerifiedAuth()
        assertNull(pendingPin)
        // Trusted Quick uses its pinned identity, never a Normal PIN.
        assertNull(IccoaNetworkPinLifecycle.afterAuthQueued(null))
        assertNull(IccoaNetworkPinLifecycle.afterVerifiedAuth())
        // A rejected credential is not a transport retry.
        assertEquals(IccoaCloseAction.STOP, IccoaCloseEvent.classify("PIN rejected").action)
        assertEquals(IccoaCloseAction.REQUIRE_NORMAL_PIN,
            IccoaCloseEvent.classify("Quick rejected", quickNormalFallback = true).action)
    }

    @Test fun typedCloseContractRejectsConflictingActions() {
        try {
            IccoaCloseEvent.classify("invalid", transportFailure = true, quickNormalFallback = true)
            fail("Conflicting recovery and PIN fallback should fail closed")
        } catch (_: IllegalArgumentException) {
            // Expected: never dispatch two incompatible terminal actions.
        }
    }

    @Test fun lostSelectedNetworkTearsDownAttemptAndNewNetworkUsesOnlyThreeTransportSlots() {
        val recovery = IccoaDropRecovery()
        assertEquals(1, recovery.onInitialRememberedCarAutoSelect())
        val attemptA = IccoaNetworkCallbackGate<String>()
        attemptA.onAvailable("A")
        assertTrue(attemptA.onLost("A"))
        val closeA = IccoaCloseEvent.classify("Wi-Fi lost", transportFailure = true)
        assertEquals(IccoaCloseAction.RETRY_TRANSPORT, closeA.action)
        attemptA.clear() // IccoaSession.close() releases old callback ownership
        assertFalse(attemptA.isCurrent("A"))

        assertEquals(2, recovery.onFailure(false, true,
            closeA.action == IccoaCloseAction.RETRY_TRANSPORT))
        val attemptB = IccoaNetworkCallbackGate<String>()
        attemptB.onAvailable("B")
        var cipher: String? = null
        val ready = mutableListOf<String>()
        // Callbacks from the torn-down attempt A cannot mutate attempt B.
        assertFalse(attemptA.commitIfCurrent("A") { cipher = "stale-A" })
        assertFalse(attemptA.onLost("A"))
        assertTrue(attemptB.commitIfCurrent("B") { cipher = "verified-B" })
        ready.add("B")
        assertEquals("verified-B", cipher)
        assertEquals(listOf("B"), ready)

        // A new transport failure may consume attempt #3, never attempt #4.
        assertTrue(attemptB.onLost("B"))
        assertEquals(3, recovery.onFailure(false, true, true))
        assertNull(recovery.onFailure(false, true, true))
        assertFalse(recovery.active)
        assertEquals(IccoaCloseAction.STOP, IccoaCloseEvent.classify("PIN rejected").action)
        assertEquals(IccoaCloseAction.REQUIRE_NORMAL_PIN,
            IccoaCloseEvent.classify("Quick rejected", quickNormalFallback = true).action)
    }

    @Test fun missingIpv4SchedulesSameNetworkRetryUntilAddressArrives() {
        val network = IccoaNetworkCallbackGate<String>()
        val retry = IccoaGatewayRetryGate<String>()
        val attempts = mutableListOf<String>()
        network.onAvailable("A")

        // onAvailable: LinkProperties exist, but DHCP has not assigned IPv4.
        assertTrue(retry.schedule("A"))
        assertFalse(retry.schedule("A"))
        // First delayed poll still has no IPv4: re-arm, don't wait for a callback.
        assertTrue(retry.consume("A"))
        assertTrue(network.isCurrent("A"))
        assertTrue(retry.schedule("A"))
        // Second poll finds IPv4 and a valid gateway: auth may start once.
        assertTrue(retry.consume("A"))
        assertTrue(network.bindIfCurrent("A") { attempts.add("A") })
        retry.clear()
        assertEquals(listOf("A"), attempts)
        assertFalse(retry.consume("A"))

        // A late timer cannot start auth after the selected network is lost.
        assertTrue(network.onLost("A"))
        assertFalse(network.bindIfCurrent("A") { attempts.add("stale-A") })
        assertEquals(listOf("A"), attempts)
    }

    @Test fun gatewayRetryBelongsToReplacementNetworkNotStaleTimer() {
        val network = IccoaNetworkCallbackGate<String>()
        val retry = IccoaGatewayRetryGate<String>()
        network.onAvailable("A")
        assertTrue(retry.schedule("A"))
        assertFalse(retry.schedule("A")) // no duplicate timer for A
        network.onAvailable("B") // B has no gateway either
        assertTrue(retry.schedule("B")) // B must receive its own retry
        assertFalse(retry.consume("A")) // late timer A cannot consume B
        assertTrue(network.isCurrent("B"))
        assertFalse(network.onLost("A"))
        assertTrue(retry.consume("B")) // B may re-read LinkProperties
        assertFalse(retry.consume("B"))
        assertTrue(retry.schedule("B")) // still missing: bounded next retry
        retry.clear() // teardown cancels logical ownership
        assertFalse(retry.consume("B"))
        assertEquals(IccoaCloseAction.REQUIRE_NORMAL_PIN,
            IccoaCloseEvent.classify("Quick rejected", quickNormalFallback = true).action)
    }

}
