package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayTouchGeometryTest {
    private val g = CarPlayTouchGeometry
    private fun f(r: CarPlayTouchGeometry.Rotation,
        pw: Int = 100, ph: Int = 50, hw: Int = 100, hh: Int = 50,
        fit: Boolean = false) =
        CarPlayTouchGeometry.Frame(pw, ph, hw, hh, fit, r)

    @Test fun identityAndKnownScaleShareCanonicalVideoMath() {
        val frame = f(g.Rotation.NONE, hw = 200, hh = 100)
        val mapped = g.inverse(100, 50, frame, true)
        assertEquals(g.Status.CANDIDATE_NOT_SENT, mapped.status)
        assertEquals(50, mapped.phoneX)
        assertEquals(25, mapped.phoneY)
    }

    @Test fun allQuarterTurnsHaveCorrectInverse() {
        val cw = f(g.Rotation.CLOCKWISE_90, hw = 50, hh = 100)
        assertEquals(0, g.inverse(0, 0, cw, true).phoneX)
        assertEquals(49, g.inverse(0, 0, cw, true).phoneY)
        assertEquals(99, g.inverse(49, 99, cw, true).phoneX)
        assertEquals(0, g.inverse(49, 99, cw, true).phoneY)
        val half = g.inverse(0, 0, f(g.Rotation.HALF_TURN), true)
        assertEquals(99, half.phoneX)
        assertEquals(49, half.phoneY)
        val ccw = g.inverse(0, 0, f(g.Rotation.CLOCKWISE_270, hw = 50, hh = 100), true)
        assertEquals(99, ccw.phoneX)
        assertEquals(0, ccw.phoneY)
    }

    @Test fun aspectBarsAndOutOfRasterNeverBecomeEdgeTouches() {
        val fit = f(g.Rotation.NONE, hw = 200, hh = 200, fit = true)
        assertEquals(g.Status.OUTSIDE_DISPLAY_OR_LETTERBOX, g.inverse(100, 49, fit, true).status)
        assertEquals(g.Status.OUTSIDE_DISPLAY_OR_LETTERBOX, g.inverse(100, 150, fit, true).status)
        assertEquals(g.Status.OUTSIDE_DISPLAY_OR_LETTERBOX, g.inverse(-1, 100, fit, true).status)
        val center = g.inverse(100, 100, fit, true)
        assertEquals(50, center.phoneX)
        assertEquals(25, center.phoneY)
    }

    @Test fun unverifiedOrInvalidNeverMapsAndDoesNotLeakTouch() {
        val frame = f(g.Rotation.NONE)
        assertEquals(g.Status.SESSION_OR_PROFILE_UNVERIFIED,
            g.inverse(20, 20, frame, false).status)
        assertNull(g.inverse(20, 20, frame, false).phoneX)
        assertEquals(g.Status.INVALID_GEOMETRY,
            g.inverse(20, 20, f(g.Rotation.NONE, pw = 0), true).status)
        assertFalse(g.inverse(20, 20, frame, true).toString().contains("20"))
    }
}
