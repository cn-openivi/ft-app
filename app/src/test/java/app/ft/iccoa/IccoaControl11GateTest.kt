package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaControl11GateTest {
    private val on = byteArrayOf(0x08, 0x01)
    private val off = byteArrayOf(0x08, 0x00)

    @Test fun defaultEventModeNeverResendsUnchangedStateEvenAfterLongTime() {
        val gate = IccoaControl11Gate()
        assertTrue(gate.shouldSend(on, 1_000L))
        gate.accepted(on, 1_000L)
        for (at in listOf(2_000L, 15_999L, 16_000L, 60_000L, 3_600_000L)) {
            assertFalse(gate.shouldSend(on, at))
        }
    }

    @Test fun optionalModeReassertsOnlyAfterFifteenSeconds() {
        val gate = IccoaControl11Gate()
        gate.accepted(on, 1_000L)
        assertFalse(gate.shouldSend(on, 15_999L, periodicActiveRefresh = true))
        assertTrue(gate.shouldSend(on, 16_000L, periodicActiveRefresh = true))
        gate.accepted(on, 16_000L)
        assertFalse(gate.shouldSend(on, 16_001L, periodicActiveRefresh = true))
        assertFalse(gate.shouldSend(on, 30_999L, periodicActiveRefresh = true))
        assertTrue(gate.shouldSend(on, 31_000L, periodicActiveRefresh = true))
    }

    @Test fun immediateStateChangeBypassesOptionalResendLimit() {
        val gate = IccoaControl11Gate()
        gate.accepted(on, 2_000L)
        assertTrue(gate.shouldSend(off, 2_001L))
        gate.accepted(off, 2_001L)
        assertTrue(gate.shouldSend(on, 2_002L, periodicActiveRefresh = true))
        gate.accepted(on, 2_002L)
        assertTrue(gate.shouldSend(byteArrayOf(0x08, 0x01, 0x10, 0x02),
            2_003L, periodicActiveRefresh = true))
    }

    @Test fun failedSendRemainsEligibleAndReconnectAlwaysSynchronizes() {
        val gate = IccoaControl11Gate()
        assertTrue(gate.shouldSend(on, 100L))
        assertTrue(gate.shouldSend(on, 150L)) // No accepted() after failure.
        gate.accepted(on, 150L)
        assertFalse(gate.shouldSend(on, 200L))
        gate.reset()
        gate.reset()
        assertTrue(gate.shouldSend(on, 201L))
    }

    @Test fun resetOfMonotonicClockDoesNotSuppressCompatibilityMode() {
        val gate = IccoaControl11Gate()
        gate.accepted(on, 20_000L)
        assertTrue(gate.shouldSend(on, 100L, periodicActiveRefresh = true))
        assertFalse(gate.shouldSend(on, 100L))
    }

    @Test fun storesDefensiveCopyOfPayload() {
        val gate = IccoaControl11Gate()
        val original = byteArrayOf(0x08, 0x01)
        gate.accepted(original, 1_000L)
        original[1] = 0
        assertFalse(gate.shouldSend(on, 1_001L))
        assertTrue(gate.shouldSend(original, 1_001L))
    }
}
