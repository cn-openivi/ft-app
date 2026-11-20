package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaPictureRecoveryGateTest {
    @Test fun boundedAutomaticRecoveryAndExhaustion() {
        val g = AaPictureRecoveryGate()
        assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(0L, true, false, false))
        assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(3999L, true, false, false))
        assertEquals(AaPictureRecoveryGate.Action.RECREATE_SURFACE, g.check(4000L, true, false, false))
        assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(5000L, true, false, false))
        assertEquals(AaPictureRecoveryGate.Action.RECREATE_SURFACE, g.check(9000L, true, false, false))
        assertEquals(AaPictureRecoveryGate.Action.SHOW_MANUAL_RETRY, g.check(14000L, true, false, false))
        repeat(30) { assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(15000L + it * 1000L, true, false, false)) }
    }

    @Test fun attachedSurfaceRequestsIdrBeforeRecreatingTexture() {
        val g = AaPictureRecoveryGate()
        assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(100L, true, false, true))
        assertEquals(AaPictureRecoveryGate.Action.REQUEST_FRESH_PICTURE, g.check(4100L, true, false, true))
        assertEquals(AaPictureRecoveryGate.Action.RECREATE_SURFACE, g.check(9100L, true, false, true))
    }

    @Test fun renderedFrameAndDisconnectResetRecovery() {
        val g = AaPictureRecoveryGate()
        g.check(0L, true, false, false)
        assertEquals(AaPictureRecoveryGate.Action.RECREATE_SURFACE, g.check(4000L, true, false, false))
        assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(4010L, true, true, true))
        assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(4020L, true, false, false))
        assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(5000L, false, false, false))
        assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(5050L, true, false, false))
    }

    @Test fun monotonicClockRewindDoesNotCauseRapidRetry() {
        val g = AaPictureRecoveryGate()
        g.check(10000L, true, false, false)
        assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(9000L, true, false, false))
        assertEquals(AaPictureRecoveryGate.Action.NONE, g.check(12000L, true, false, false))
        assertEquals(AaPictureRecoveryGate.Action.RECREATE_SURFACE, g.check(13000L, true, false, false))
    }
}
