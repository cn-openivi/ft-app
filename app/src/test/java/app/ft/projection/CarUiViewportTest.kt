package app.ft.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarUiViewportTest {
    @Test fun sameAspectKeepsFTAppIconsAndTextTheSameVisualProportion() {
        assertEquals(160, CarUiViewport.logicalDpi(1280, 720))
        assertEquals(240, CarUiViewport.logicalDpi(1920, 1080))
        assertEquals(320, CarUiViewport.logicalDpi(2560, 1440))
        assertEquals(480, CarUiViewport.logicalDpi(3840, 2160))

        // A logical 72dp icon is 72px on 720p and 216px on 4K:
        // in each case it is 10% of the height, not three different sizes.
        val sizes = listOf(720 to 160, 1080 to 240, 2160 to 480)
        val fractions = sizes.map { (height, dpi) -> 72f * dpi / 160f / height }
        fractions.forEach { assertEquals(0.1f, it, 0.00001f) }
    }

    @Test fun negotiatedChineseXa60ViewportUsesHeightFitNotFixed240Dpi() {
        // The 2422x1272 negotiated content aspect is slightly wider than 16:9.
        assertEquals(283, CarUiViewport.logicalDpi(2422, 1272))
        assertEquals(160, CarUiViewport.logicalDpi(2560, 720))
        assertEquals(100, CarUiViewport.logicalDpi(800, 480))
    }

    @Test fun portraitAndRotationRecomputeWithoutMediaOrTouchCoordinateChanges() {
        assertEquals(160, CarUiViewport.logicalDpi(720, 1280))
        assertEquals(240, CarUiViewport.logicalDpi(1080, 1920))
        assertEquals(160, CarUiViewport.logicalDpi(1280, 720))
    }

    @Test fun legacyDpiIsRelativeUserZoomNotAForcedDisplayDensity() {
        assertEquals(160, CarUiViewport.logicalDpi(1280, 720, 0))
        assertEquals(240, CarUiViewport.logicalDpi(1280, 720, 240))
        assertEquals(360, CarUiViewport.logicalDpi(1920, 1080, 240))
        assertEquals(160, CarUiViewport.logicalDpi(0, 0, 160))
        assertTrue(CarUiViewport.logicalDpi(3840, 2160, 320) <= 640)
    }
}
