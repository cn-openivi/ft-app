package app.ft.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CarUiViewportTest {
    @Test fun neutral160RestoresPhysicalOwnerHuScaleRatherThan424() {
        assertEquals(159, CarUiViewport.logicalDpi(2422, 1272))
        assertEquals(159, CarUiViewport.logicalDpi(1272, 2422))
        assertEquals(111, CarUiViewport.logicalDpi(2422, 1272, 112))
        assertEquals(254, CarUiViewport.logicalDpi(2422, 1272, 256))
    }

    @Test fun genericResolutionsHaveStableProportionalSize() {
        assertEquals(90, CarUiViewport.logicalDpi(1280, 720))
        assertEquals(135, CarUiViewport.logicalDpi(1920, 1080))
        assertEquals(270, CarUiViewport.logicalDpi(3840, 2160))
        assertEquals(90, CarUiViewport.logicalDpi(2560, 720))
        val fractions = listOf(720 to 90, 1080 to 135, 2160 to 270).map {
            (height, dpi) -> 44f * dpi / 160f / height
        }
        fractions.forEach { assertEquals(44f / 1280f, it, 0.00001f) }
    }

    @Test fun portraitAndInvalidInputKeepSafeBounds() {
        assertEquals(90, CarUiViewport.logicalDpi(720, 1280))
        assertEquals(135, CarUiViewport.logicalDpi(1080, 1920))
        assertEquals(80, CarUiViewport.logicalDpi(800, 480))
        assertEquals(160, CarUiViewport.logicalDpi(0, 0, 160))
        assertEquals(90, CarUiViewport.logicalDpi(1280, 720, 0))
        assertTrue(CarUiViewport.logicalDpi(7680, 4320, 320) <= 1280)
    }

    @Test fun sixVisualZoomStepsStayOrderedAndIndependentOfAaDpi() {
        val p = FtUiScaleStep.entries
        val d = p.map { CarUiViewport.logicalDpi(2422, 1272, it.storedRelativeScale) }
        assertEquals(p.size, d.toSet().size)
        d.zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
        for (preset in p) {
            val d720 = CarUiViewport.logicalDpi(1280, 720, preset.storedRelativeScale)
            val d4k = CarUiViewport.logicalDpi(3840, 2160, preset.storedRelativeScale)
            assertTrue(preset.name, abs(d4k - 3*d720) <= 2)
        }
        assertEquals(FtUiScaleStep.NORMAL, FtUiScaleStep.fromLegacy(160))
    }
}
