package app.ft.iccoa

import app.ft.aa.AaVideoLayout
import app.ft.aa.AaViewport
import app.ft.aa.AaTouchGestureViewport
import org.junit.Assert.*
import org.junit.Test

class IccoaAaSizeTest {
    @Test fun xa60SelectsFullHdRatherThanLegacy1008() {
        val (w, h) = IccoaAaSize.fit(2422, 1272)
        assertEquals(1920 to 1080, w to h)
        val layout = AaVideoLayout.forFrame(w, h, 1920, 1080)
        assertEquals(1920 to 1080, layout.contentWidth to layout.contentHeight)
        assertEquals(1f, layout.scaleY, 0f)
    }

    @Test fun carAspectsCannotProduceCustomAaWireSizes() {
        val verified = setOf(800 to 480, 1280 to 720, 1920 to 1080)
        for ((w, h) in listOf(800 to 480, 1280 to 720, 1920 to 1080,
            1920 to 1008, 1920 to 720, 2422 to 1272, 3840 to 2160,
            1080 to 2160, 3840 to 960, 320 to 240)) {
            assertTrue("not a supported AA mode", IccoaAaSize.fit(w, h) in verified)
        }
        assertEquals(1920 to 1080, IccoaAaSize.fit(1920, 720))
        assertEquals(1920 to 1080, IccoaAaSize.fit(3840, 960))
    }

    @Test fun onlyMeasuredAaPictureGetsTouchesNotSideMenuOrClimate() {
        // Synthetic Compose-measured rectangle, not assumed Toyota dimensions.
        val vp = AaViewport(150, 70, 1600, 900)
        val touch = AaTouchGestureViewport()
        assertNull(touch.map(0, 40, 500, vp, 1920, 1080)) // side menu
        assertNull(touch.map(0, 800, 990, vp, 1920, 1080)) // climate strip
        assertEquals(960 to 540, touch.map(0, 950, 520, vp, 1920, 1080))
        assertEquals(960 to 540, touch.map(1, 950, 520, vp, 1920, 1080))
    }
}
