package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaControl11GateTest {
    @Test fun unchangedStateIsNeverResentJustBecauseTimeHasPassed() {
        val gate = IccoaControl11Gate()
        val active = byteArrayOf(0x08, 0x01)
        assertTrue(gate.shouldSend(active))
        gate.accepted(active)
        // No speculative OEM expiry or time-based refresh for a stateful command.
        repeat(100) { assertFalse(gate.shouldSend(active)) }
    }

    @Test fun stopAndGuidanceChangesAreNeverSuppressed() {
        val gate = IccoaControl11Gate()
        val on = byteArrayOf(0x08, 0x01)
        val off = byteArrayOf(0x08, 0x00)
        gate.accepted(on)
        assertTrue(gate.shouldSend(off))
        gate.accepted(off)
        assertTrue(gate.shouldSend(on))
        // Content change other than the active flag also goes immediately.
        gate.accepted(on)
        assertTrue(gate.shouldSend(byteArrayOf(0x08, 0x01, 0x10, 0x02)))
    }

    @Test fun failedQueueCanRetryAndNewControlLaneForcesStateSync() {
        val gate = IccoaControl11Gate()
        val on = byteArrayOf(0x08, 0x01)
        assertTrue(gate.shouldSend(on))
        assertTrue(gate.shouldSend(on)) // Transport did not enqueue, no accepted().
        gate.accepted(on)
        assertFalse(gate.shouldSend(on))
        gate.reset()
        assertTrue(gate.shouldSend(on))
    }

    @Test fun copiesPayloadBeforeStoringPreviousState() {
        val gate = IccoaControl11Gate()
        val buffer = byteArrayOf(0x08, 0x01)
        gate.accepted(buffer)
        buffer[1] = 0
        assertFalse(gate.shouldSend(byteArrayOf(0x08, 0x01)))
        assertTrue(gate.shouldSend(buffer))
    }

    @Test fun repeatedDisconnectAndReconnectRemainIdempotent() {
        val gate = IccoaControl11Gate()
        val off = byteArrayOf(0x08, 0x00)
        gate.accepted(off)
        gate.reset()
        gate.reset()
        assertTrue(gate.shouldSend(off))
        gate.accepted(off)
        assertFalse(gate.shouldSend(off))
    }
}
