package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaControl11GateTest {
    @Test fun duplicateNavigationDoesNotSpamOemControlLane() {
        val gate = IccoaControl11Gate()
        val active = byteArrayOf(0x08, 0x01)
        assertTrue(gate.shouldSend(active, 100L))
        gate.accepted(active, 100L)
        assertFalse(gate.shouldSend(active, 200L))
        assertFalse(gate.shouldSend(active, 15_099L))
        assertTrue(gate.shouldSend(active, 15_100L))
    }

    @Test fun stopAndGuidanceChangeAreNeverSuppressed() {
        val gate = IccoaControl11Gate()
        val on = byteArrayOf(0x08, 0x01)
        val off = byteArrayOf(0x08, 0x00)
        gate.accepted(on, 1_000)
        assertTrue(gate.shouldSend(off, 1_001))
        gate.accepted(off, 1_001)
        assertTrue(gate.shouldSend(on, 1_002))
    }

    @Test fun unqueuedMessageMayRetryAndControlReconnectForcesRefresh() {
        val gate = IccoaControl11Gate()
        val on = byteArrayOf(0x08, 0x01)
        assertTrue(gate.shouldSend(on, 1_000))
        assertTrue(gate.shouldSend(on, 1_050))
        gate.accepted(on, 1_050)
        assertFalse(gate.shouldSend(on, 1_060))
        gate.reset()
        assertTrue(gate.shouldSend(on, 1_061))
        gate.accepted(on, 1_061)
        assertTrue(gate.shouldSend(on, 800)) // monotonic reset guard
    }

    @Test fun copiesPayloadBeforeStoringPreviousState() {
        val gate = IccoaControl11Gate()
        val buffer = byteArrayOf(0x08, 0x01)
        gate.accepted(buffer, 100)
        buffer[1] = 0
        assertFalse(gate.shouldSend(byteArrayOf(0x08, 0x01), 101))
        assertTrue(gate.shouldSend(buffer, 101))
    }
}
