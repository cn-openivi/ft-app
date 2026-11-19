package app.ft.iccoa

import app.ft.aa.AaVideoLayout
import app.ft.aa.AaViewport
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

    @Test fun standardFullHdTouchViewportCoversAllInputCorners() {
        val vp = AaViewport(0, 0, 2422, 1272)
        assertEquals(960 to 540, vp.map(1211, 636, 1920, 1080))
        assertEquals(1919 to 1079, vp.map(2421, 1271, 1920, 1080))
    }
}
