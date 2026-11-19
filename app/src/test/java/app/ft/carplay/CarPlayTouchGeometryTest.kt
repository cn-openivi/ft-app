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
        val frame = f(CarPlayTouchGeometry.Rotation.NONE, hw = 200, hh = 100)
        val mapped = g.inverse(100, 50, frame, true)
        assertEquals(CarPlayTouchGeometry.Status.CANDIDATE_NOT_SENT, mapped.status)
        assertEquals(50, mapped.phoneX)
        assertEquals(25, mapped.phoneY)
    }

    @Test fun allQuarterTurnsHaveCorrectInverse() {
        val cw = f(CarPlayTouchGeometry.Rotation.CLOCKWISE_90, hw = 50, hh = 100)
        assertEquals(0, g.inverse(0, 0, cw, true).phoneX)
        assertEquals(49, g.inverse(0, 0, cw, true).phoneY)
        assertEquals(99, g.inverse(49, 99, cw, true).phoneX)
        assertEquals(0, g.inverse(49, 99, cw, true).phoneY)
        val half = g.inverse(0, 0, f(CarPlayTouchGeometry.Rotation.HALF_TURN), true)
        assertEquals(99, half.phoneX)
        assertEquals(49, half.phoneY)
        val ccw = g.inverse(0, 0, f(CarPlayTouchGeometry.Rotation.CLOCKWISE_270, hw = 50, hh = 100), true)
        assertEquals(99, ccw.phoneX)
        assertEquals(0, ccw.phoneY)
    }

    @Test fun aspectBarsAndOutOfRasterNeverBecomeEdgeTouches() {
        val fit = f(CarPlayTouchGeometry.Rotation.NONE, hw = 200, hh = 200, fit = true)
        assertEquals(CarPlayTouchGeometry.Status.OUTSIDE_DISPLAY_OR_LETTERBOX, g.inverse(100, 49, fit, true).status)
        assertEquals(CarPlayTouchGeometry.Status.OUTSIDE_DISPLAY_OR_LETTERBOX, g.inverse(100, 150, fit, true).status)
        assertEquals(CarPlayTouchGeometry.Status.OUTSIDE_DISPLAY_OR_LETTERBOX, g.inverse(-1, 100, fit, true).status)
        val center = g.inverse(100, 100, fit, true)
        assertEquals(50, center.phoneX)
        assertEquals(25, center.phoneY)
    }

    @Test fun leftRailAndBottomClimateControlTouchesAreExcluded() {
        val video = f(CarPlayTouchGeometry.Rotation.NONE,hw=200,hh=100)
        val viewport = CarPlayTouchGeometry.HuViewport(40,20,200,100)
        // The total HU UI remains 300x180; only 200x100 holds projection.
        val center = g.inverseFromHuScreen(140,70,300,180,viewport,video,true)
        assertEquals(CarPlayTouchGeometry.Status.CANDIDATE_NOT_SENT,center.status)
        assertEquals(50,center.phoneX)
        assertEquals(25,center.phoneY)
        assertEquals(CarPlayTouchGeometry.Status.OUTSIDE_DISPLAY_OR_LETTERBOX,
            g.inverseFromHuScreen(20,70,300,180,viewport,video,true).status)
        assertEquals(CarPlayTouchGeometry.Status.OUTSIDE_DISPLAY_OR_LETTERBOX,
            g.inverseFromHuScreen(140,145,300,180,viewport,video,true).status)
        assertEquals(CarPlayTouchGeometry.Status.OUTSIDE_DISPLAY_OR_LETTERBOX,
            g.inverseFromHuScreen(240,70,300,180,viewport,video,true).status)
    }

    @Test fun actualViewportAndRotationAreRequiredAndNoFullscreenGuessing() {
        val video = f(CarPlayTouchGeometry.Rotation.CLOCKWISE_90,hw=50,hh=100)
        val viewport = CarPlayTouchGeometry.HuViewport(25,10,50,100)
        val mapped = g.inverseFromHuScreen(25,10,130,150,viewport,video,true)
        assertEquals(0,mapped.phoneX)
        assertEquals(49,mapped.phoneY)
        assertEquals(CarPlayTouchGeometry.Status.SESSION_OR_PROFILE_UNVERIFIED,
            g.inverseFromHuScreen(25,10,130,150,viewport,video,false).status)
        assertEquals(CarPlayTouchGeometry.Status.INVALID_GEOMETRY,
            g.inverseFromHuScreen(25,10,130,150,viewport.copy(left=120),video,true).status)
        assertEquals(CarPlayTouchGeometry.Status.INVALID_GEOMETRY,
            g.inverseFromHuScreen(25,10,130,150,viewport.copy(width=49),video,true).status)
        assertEquals(CarPlayTouchGeometry.Status.OUTSIDE_DISPLAY_OR_LETTERBOX,
            g.inverseFromHuScreen(-1,40,130,150,viewport,video,true).status)
    }

    @Test fun unverifiedOrInvalidNeverMapsAndDoesNotLeakTouch() {
        val frame = f(CarPlayTouchGeometry.Rotation.NONE)
        assertEquals(CarPlayTouchGeometry.Status.SESSION_OR_PROFILE_UNVERIFIED,
            g.inverse(20, 20, frame, false).status)
        assertNull(g.inverse(20, 20, frame, false).phoneX)
        assertEquals(CarPlayTouchGeometry.Status.INVALID_GEOMETRY,
            g.inverse(20, 20, f(CarPlayTouchGeometry.Rotation.NONE, pw = 0), true).status)
        assertFalse(g.inverse(20, 20, frame, true).toString().contains("20"))
    }
}
