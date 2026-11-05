package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaNetworkCallbackGateTest {
    @Test fun staleNetworkCannotBindOrCommitAfterReplacement() {
        val gate = IccoaNetworkCallbackGate<String>()
        assertTrue(gate.onAvailable("A"))
        val bound = mutableListOf<String>()
        assertTrue(gate.bindIfCurrent("A") { bound += "A" })
        assertTrue(gate.onAvailable("B"))
        assertFalse(gate.bindIfCurrent("A") { bound += "stale" })
        assertFalse(gate.commitIfCurrent("A") { bound += "stale-keys" })
        assertEquals(listOf("A"), bound)
        assertTrue(gate.bindIfCurrent("B") { bound += "B" })
        assertTrue(gate.commitIfCurrent("B") { bound += "keys-B" })
        assertEquals(listOf("A", "B", "keys-B"), bound)
    }

    @Test fun delayedLossFromOldNetworkCannotCloseNewSession() {
        val gate = IccoaNetworkCallbackGate<String>()
        gate.onAvailable("A")
        gate.onAvailable("B")
        assertFalse(gate.onLost("A"))
        assertTrue(gate.isCurrent("B"))
        assertTrue(gate.onLost("B"))
        assertTrue(gate.isUnreplacedLoss("B"))
        // onAvailable(C) can run before a previously queued fail(B).
        gate.onAvailable("C")
        assertFalse(gate.isUnreplacedLoss("B"))
        assertTrue(gate.isCurrent("C"))
    }

    @Test fun pendingLossIsCancelledWhenReplacementArrives() {
        val gate = IccoaNetworkCallbackGate<String>()
        gate.onAvailable("A")
        assertTrue(gate.onLost("A"))
        assertTrue(gate.isUnreplacedLoss("A"))
        gate.onAvailable("B")
        assertFalse(gate.isUnreplacedLoss("A"))
        assertFalse(gate.onLost("A"))
        gate.clear()
        assertFalse(gate.isCurrent("B"))
        assertFalse(gate.isUnreplacedLoss("A"))
    }

    @Test fun sameNetworkRepeatedCallbackDoesNotResetAuthenticationOwner() {
        val gate = IccoaNetworkCallbackGate<String>()
        assertTrue(gate.onAvailable("A"))
        assertFalse(gate.onAvailable("A"))
        assertTrue(gate.isCurrent("A"))
    }
}
