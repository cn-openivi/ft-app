package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaVideoRecoveryGateTest {
    @Test fun firstLostFrameRequestsRecoveryAndNextBurstIsBounded() {
        val gate = AaVideoRecoveryGate()
        assertTrue(gate.onLoss(0))
        repeat(100) { assertFalse(gate.onLoss(100_000_000L + it)) }
        assertFalse(gate.onLoss(2_499_999_999L))
        assertTrue(gate.onLoss(2_500_000_000L))
        assertFalse(gate.onLoss(2_600_000_000L))
    }

    @Test fun newDecoderGenerationCanRequestFreshFrameImmediately() {
        val gate = AaVideoRecoveryGate()
        assertTrue(gate.onLoss(10_000L))
        gate.reset()
        assertTrue(gate.onLoss(10_001L))
        // Handle a monotonic clock anomaly conservatively without a permanent block.
        assertTrue(gate.onLoss(3L))
    }
}
