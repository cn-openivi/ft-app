package app.ft.iccoa

import app.ft.projection.CarTouch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IccoaTwoFingerReturnGestureTest {
    private val pass = IccoaTwoFingerReturnGesture.Decision.PASS
    private val start = IccoaTwoFingerReturnGesture.Decision.START_HOLD
    private val holding = IccoaTwoFingerReturnGesture.Decision.HOLDING
    private val consume = IccoaTwoFingerReturnGesture.Decision.CONSUME

    @Test fun singleFingerNeverStartsReturnTimer() {
        val g = IccoaTwoFingerReturnGesture()
        assertEquals(pass, g.observe(CarTouch(CarTouch.DOWN, 200, 220), 0))
        assertEquals(pass, g.observe(CarTouch(CarTouch.MOVE, 201, 220), 500))
        assertFalse(g.fire(50_000))
        assertEquals(pass, g.observe(CarTouch(CarTouch.UP, 201, 220), 50_000))
    }

    @Test fun stationaryTwoFingersRequireFullTwoPointFiveSeconds() {
        val g = IccoaTwoFingerReturnGesture()
        assertEquals(pass, g.observe(CarTouch(CarTouch.DOWN, 100, 120), 100))
        assertEquals(start, g.observe(CarTouch(CarTouch.POINTER_DOWN, 100, 120, 400, 440), 700))
        assertFalse(g.fire(3199))
        assertEquals(holding, g.observe(CarTouch(CarTouch.MOVE, 101, 119, 401, 442), 3199))
        assertTrue(g.fire(3200))
        assertFalse(g.fire(3500)) // one action per hold
        assertEquals(consume, g.observe(CarTouch(CarTouch.POINTER_UP, 100, 120, 400, 440), 3501))
        assertEquals(consume, g.observe(CarTouch(CarTouch.UP, 100, 120), 3520))
        assertFalse(g.fire(10000))
    }

    @Test fun earlyReleaseCannotReturnAndConsumesTrailingFinger() {
        val g = IccoaTwoFingerReturnGesture()
        g.observe(CarTouch(CarTouch.DOWN, 0, 0), 0)
        g.observe(CarTouch(CarTouch.POINTER_DOWN, 0, 0, 60, 50), 100)
        assertEquals(consume, g.observe(CarTouch(CarTouch.POINTER_UP, 0, 0, 60, 50), 600))
        assertFalse(g.fire(10_000))
        assertEquals(consume, g.observe(CarTouch(CarTouch.UP, 0, 0), 700))
        assertEquals(pass, g.observe(CarTouch(CarTouch.DOWN, 0, 0), 800))
    }

    @Test fun movingTwoFingersCannotTriggerAndRestartPrimaryTouch() {
        val g = IccoaTwoFingerReturnGesture()
        g.observe(CarTouch(CarTouch.DOWN, 100, 120), 0)
        g.observe(CarTouch(CarTouch.POINTER_DOWN, 100, 120, 400, 440), 100)
        assertEquals(IccoaTwoFingerReturnGesture.Decision.RESTART_PRIMARY,
            g.observe(CarTouch(CarTouch.MOVE, 140, 120, 400, 440), 500))
        assertFalse(g.fire(5000))
        assertEquals(pass, g.observe(CarTouch(CarTouch.MOVE, 160, 120, 400, 440), 600))
    }

    @Test fun supportsReceiversThatSendTwoFingerDownOrMoveWithoutPointerDown() {
        val g = IccoaTwoFingerReturnGesture()
        assertEquals(start, g.observe(CarTouch(CarTouch.DOWN, 30, 40, 70, 80), 0))
        assertTrue(g.fire(2500))
        g.reset()
        assertEquals(pass, g.observe(CarTouch(CarTouch.DOWN, 30, 40), 3000))
        assertEquals(start, g.observe(CarTouch(CarTouch.MOVE, 30, 40, 70, 80), 3500))
        assertTrue(g.fire(6000))
    }

    @Test fun cancellingOrChangingRouteInvalidatesPendingHold() {
        val g = IccoaTwoFingerReturnGesture()
        g.observe(CarTouch(CarTouch.POINTER_DOWN, 0, 0, 50, 50), 0)
        assertEquals(consume, g.observe(CarTouch(CarTouch.CANCEL, 0, 0), 500))
        assertFalse(g.fire(9000))
        g.observe(CarTouch(CarTouch.DOWN, 1, 2, 31, 32), 10000)
        g.reset()
        assertFalse(g.fire(20000))
    }

    @Test fun twentyRapidPinchAttemptsNeverReturnToFt() {
        val g = IccoaTwoFingerReturnGesture()
        for (i in 0 until 20) {
            val t = i * 10000L
            assertEquals(pass, g.observe(CarTouch(CarTouch.DOWN, 50, 80), t))
            assertEquals(start, g.observe(CarTouch(CarTouch.POINTER_DOWN, 50, 80, 100, 160), t + 20))
            assertEquals(IccoaTwoFingerReturnGesture.Decision.RESTART_PRIMARY,
                g.observe(CarTouch(CarTouch.MOVE, 50, 80, 170, 160), t + 100))
            assertFalse(g.fire(t + 5000))
        }
    }
}
