package app.ft.iccoa

import app.ft.aa.AaVideoLayout
import app.ft.projection.TouchVideoGeometry
import app.ft.projection.VideoPlan
import org.junit.Assert.*
import org.junit.Test

class IccoaAaVideoProfileTest {
    private fun hu(wfdWidth: Int = 1920, wfdHeight: Int = 1080, fps: Int = 30) =
        VideoPlan(2422, 1272, wfdWidth, wfdHeight, fps, 8_000_000,
            preserveAspect = true)

    @Test fun xa60DirectUsesSameStandard1920x1080ForAaAndWfd() {
        val profile = IccoaAaVideoProfile.choose(hu(), true, true, 1920, 1008)
        assertEquals(1920 to 1080, profile.width to profile.height)
        assertTrue(profile.directStandard)
        assertTrue(profile.applyToPrefs)
        val layout = AaVideoLayout.forFrame(profile.width, profile.height, 1920, 1080)
        assertEquals(1920 to 1080, layout.contentWidth to layout.contentHeight)
        assertEquals(1f, layout.scaleX, 0f)
        assertEquals(1f, layout.scaleY, 0f)
    }

    @Test fun directUses720pWhenWfdActuallySelected720p() {
        val profile = IccoaAaVideoProfile.choose(hu(1280, 720), true, false, 1920, 1008)
        assertEquals(1280 to 720, profile.width to profile.height)
        assertTrue(profile.directStandard)
    }

    @Test fun nonstandardWfdAndUnsupportedFpsRemainSafeCompositedFallbacks() {
        val odd = IccoaAaVideoProfile.choose(hu(1600, 900), true, true, 1920, 1008)
        assertFalse(odd.directStandard)
        assertEquals(1920 to 1008, odd.width to odd.height)
        val fast = IccoaAaVideoProfile.choose(hu(1920, 1080, 60), true, true, 1920, 1008)
        assertFalse(fast.directStandard)
        val manual = IccoaAaVideoProfile.choose(hu(1600, 900), true, false, 1280, 720)
        assertFalse(manual.applyToPrefs)
        assertEquals(1280 to 720, manual.width to manual.height)
    }

    @Test fun ordinaryCompositedAaStillFitsHuWithoutStretching() {
        val profile = IccoaAaVideoProfile.choose(hu(), false, true, 1280, 720)
        assertFalse(profile.directStandard)
        assertEquals(1920 to 1008, profile.width to profile.height)
        val geometry = TouchVideoGeometry(2422, 1272, 1920, 1080, true)
        assertEquals(960 to 540, geometry.toStream(1211, 636))
        assertEquals(1211 to 636, geometry.toContent(960, 540))
        assertEquals(0 to 36, geometry.toStream(0, 0))
    }
}
