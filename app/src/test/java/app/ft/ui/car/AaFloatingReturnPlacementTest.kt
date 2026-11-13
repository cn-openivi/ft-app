package app.ft.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class AaFloatingReturnPlacementTest {
    @Test fun holdToDragRequiresFourSeconds() {
        assertEquals(4_000L, AaFloatingReturnPlacement.HOLD_MS)
    }

    @Test fun cornerDefaultsKeepPreviousAaCornerPlacement() {
        assertEquals(AaFloatingReturnPlacement.Point(0f, 1f), AaFloatingReturnPlacement.fromCorner(2))
        assertEquals(AaFloatingReturnPlacement.Point(1f, 1f), AaFloatingReturnPlacement.fromCorner(3))
    }

    @Test fun dragIsBoundedAndResolutionIndependent() {
        val original = AaFloatingReturnPlacement.Point(.5f, .5f)
        val shifted = AaFloatingReturnPlacement.dragBy(original, 100f, -50f, 400, 200)
        assertEquals(.75f, shifted.x, .00001f)
        assertEquals(.25f, shifted.y, .00001f)
        assertEquals(293, AaFloatingReturnPlacement.pixel(shifted.x, 448, 48, 14))
        assertEquals(73, AaFloatingReturnPlacement.pixel(shifted.y, 312, 48, 14))
        assertEquals(593, AaFloatingReturnPlacement.pixel(shifted.x, 848, 48, 14))
    }

    @Test fun dragCannotLeaveScreenOrDivideByZero() {
        val initial = AaFloatingReturnPlacement.Point(.5f, .5f)
        assertEquals(AaFloatingReturnPlacement.Point(1f, 0f),
            AaFloatingReturnPlacement.dragBy(initial, 4000f, -4000f, 200, 200))
        assertEquals(initial, AaFloatingReturnPlacement.dragBy(initial, 2f, 4f, 0, 0))
        assertEquals(0, AaFloatingReturnPlacement.pixel(1f, 40, 48, 14))
        assertEquals(14, AaFloatingReturnPlacement.pixel(Float.NaN, 480, 48, 14))
    }
}
