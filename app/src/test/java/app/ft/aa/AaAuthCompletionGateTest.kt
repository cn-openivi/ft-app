package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaAuthCompletionGateTest {
    @Test fun tlsHandshakeMayNeedManyRecordsButAuthIsEmittedOnlyOnce() {
        val gate = AaAuthCompletionGate()
        assertFalse(gate.shouldSend(false))
        assertFalse(gate.shouldSend(false))
        assertTrue(gate.shouldSend(true))
        // Replayed or delayed SSL_HANDSHAKE packets after TLS completion
        // must not re-advertise AUTH_COMPLETE or revert phase to AUTHENTICATED.
        repeat(20) { assertFalse(gate.shouldSend(true)) }
        assertFalse(gate.shouldSend(false))
        assertFalse(gate.shouldSend(true))
    }

    @Test fun newAaSessionHasIndependentCompletionLifecycle() {
        val first = AaAuthCompletionGate()
        assertTrue(first.shouldSend(true))
        val next = AaAuthCompletionGate()
        assertTrue(next.shouldSend(true))
        assertFalse(first.shouldSend(true))
        assertFalse(next.shouldSend(true))
    }
}
