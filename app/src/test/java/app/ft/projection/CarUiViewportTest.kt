package app.ft.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarUiViewportTest {
    @Test fun normal160RestoresUpstream480dpHeightAndScalesWithVideo() {
        assertEquals(160, CarUiViewport.logicalDpi(800, 480))
        assertEquals(240, CarUiViewport.logicalDpi(1280, 720))
        assertEquals(360, CarUiViewport.logicalDpi(1920, 1080))
        assertEquals(480, CarUiViewport.logicalDpi(2560, 1440))
        assertEquals(720, CarUiViewport.logicalDpi(3840, 2160))

        // An unchanged 44dp FT icon is 66px at 720p (original behavior).
        // Its relative visual size does not change with resolution.
        val sizes = listOf(720 to 240, 1080 to 360, 2160 to 720)
        val fractions = sizes.map { (height, dpi) -> 44f * dpi / 160f / height }
        fractions.forEach { assertEquals(44f / 480f, it, 0.00001f) }
    }

    @Test fun originalHeightBasedScalingHandlesRealAndWideViewportShapes() {
        // Do not hard-code a Toyota-specific width, or treat video width as UI DPI.
        assertEquals(424, CarUiViewport.logicalDpi(2422, 1272))
        assertEquals(240, CarUiViewport.logicalDpi(2560, 720))
        assertEquals(160, CarUiViewport.logicalDpi(800, 480))
    }

    @Test fun portraitRotationUsesTheSameShortSideAsTheLandscapeViewport() {
        assertEquals(240, CarUiViewport.logicalDpi(720, 1280))
        assertEquals(360, CarUiViewport.logicalDpi(1080, 1920))
        assertEquals(240, CarUiViewport.logicalDpi(1280, 720))
    }

    @Test fun legacy160IsNeutralRelativeFtZoomNotAbsoluteDisplayDpi() {
        assertEquals(240, CarUiViewport.logicalDpi(1280, 720, 0))
        assertEquals(240, CarUiViewport.logicalDpi(1280, 720, 160))
        assertEquals(360, CarUiViewport.logicalDpi(1280, 720, 240))
        assertEquals(540, CarUiViewport.logicalDpi(1920, 1080, 240))
        assertEquals(160, CarUiViewport.logicalDpi(0, 0, 160))
        assertTrue(CarUiViewport.logicalDpi(7680, 4320, 320) <= 1280)
    }

    @Test fun allSixFtPresetsStayRelativeBetween720pAndUhd() {
        for (preset in FtUiScaleStep.entries) {
            val d720 = CarUiViewport.logicalDpi(1280, 720, preset.storedRelativeScale)
            val d4k = CarUiViewport.logicalDpi(3840, 2160, preset.storedRelativeScale)
            assertEquals(preset.name, 3 * d720, d4k)
        }
        // Android Auto's independent aaDensity is not read by CarUiViewport.
        assertEquals(FtUiScaleStep.NORMAL, FtUiScaleStep.fromLegacy(160))
    }
}
