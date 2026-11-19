package app.ft.iccoa

import app.ft.aa.AaVideoLayout
import app.ft.projection.TouchVideoGeometry
import app.ft.projection.VideoPlan
import org.junit.Assert.*
import org.junit.Test

class IccoaAaVideoProfileTest {
    private fun hu(w: Int = 1920, h: Int = 1080, fps: Int = 30) =
        VideoPlan(2422, 1272, w, h, fps, 8_000_000, preserveAspect = true)

    @Test fun directFullHdMatchesWfdAndNoPaddedRows() {
        val p = IccoaAaVideoProfile.choose(hu(), true, true, 1920, 1008)
        assertEquals(1920 to 1080, p.width to p.height)
        assertTrue(p.directStandard)
        assertTrue(p.applyToPrefs)
        assertEquals(1f,
            AaVideoLayout.forFrame(p.width, p.height, 1920, 1080).scaleY, 0f)
    }

    @Test fun manual720pStillDoesNotSilentlyBecome1080p() {
        val direct = IccoaAaVideoProfile.choose(hu(1280, 720), true, false, 1280, 720)
        assertEquals(1280 to 720, direct.width to direct.height)
        assertTrue(direct.directStandard)
        assertFalse(direct.applyToPrefs)
        val lower = IccoaAaVideoProfile.choose(hu(), true, false, 1280, 720)
        assertFalse(lower.directStandard)
        assertEquals(1280 to 720, lower.width to lower.height)
    }

    @Test fun legacyManual1008MigratesBothDirectAndComposited() {
        val p = IccoaAaVideoProfile.choose(hu(), false, false, 1920, 1008)
        assertEquals(1920 to 1080, p.width to p.height)
        assertTrue(p.applyToPrefs)
        val direct = IccoaAaVideoProfile.choose(hu(), true, false, 1920, 1008)
        assertEquals(1920 to 1080, direct.width to direct.height)
        assertTrue(direct.directStandard)
        assertTrue(direct.applyToPrefs)
    }

    @Test fun nonstandardWfdOr60fpsCannotAdmitDirect() {
        val odd = IccoaAaVideoProfile.choose(hu(1600, 900), true, true, 1920, 1008)
        assertEquals(1920 to 1080, odd.width to odd.height)
        assertFalse(odd.directStandard)
        val qhd = IccoaAaVideoProfile.choose(hu(2560, 1440), true, true, 2560, 1440)
        assertEquals(1920 to 1080, qhd.width to qhd.height)
        assertFalse(qhd.directStandard)
        assertFalse(IccoaAaVideoProfile.choose(hu(1920, 1080, 60),
            true, true, 1920, 1080).directStandard)
    }

    @Test fun compositedUsesStandardFullHdAndSeparateCarFit() {
        val p = IccoaAaVideoProfile.choose(hu(), false, true, 1280, 720)
        assertEquals(1920 to 1080, p.width to p.height)
        assertFalse(p.directStandard)
        val geometry = TouchVideoGeometry(2422, 1272, 1920, 1080, true)
        assertEquals(960 to 540, geometry.toStream(1211, 636))
        assertEquals(1211 to 636, geometry.toContent(960, 540))
        assertEquals(0 to 36, geometry.toStream(0, 0))
    }

    @Test fun autoQualityDependsOnWfdVideoNotOnPhysicalOemMenuOrClimate() {
        // Two completely different FT virtual-display aspect ratios.
        // The OEM side menu and climate bar can alter physical visible area;
        // neither number is a valid input to AA VIDEO resolution selection.
        val carA = VideoPlan(2422, 1272, 1280, 720, 30, 8_000_000,
            preserveAspect = true)
        val carB = VideoPlan(1920, 480, 1280, 720, 30, 8_000_000,
            preserveAspect = true)
        val a = IccoaAaVideoProfile.choose(carA, false, true, 1920, 1008)
        val b = IccoaAaVideoProfile.choose(carB, false, true, 1920, 1008)
        assertEquals(1280 to 720, a.width to a.height)
        assertEquals(a.width to a.height, b.width to b.height)
        assertTrue(a.applyToPrefs)
        // The manual Full HD choice is independent of WFD and remains Full HD.
        val manual = IccoaAaVideoProfile.choose(carA, false, false, 1920, 1080)
        assertEquals(1920 to 1080, manual.width to manual.height)
    }

    @Test fun allManualAndAutomaticPathsStayInVerifiedAaModes() {
        val verified = setOf(800 to 480, 1280 to 720, 1920 to 1080)
        for (direct in listOf(true, false)) for (auto in listOf(true, false))
            for ((w, h) in listOf(1920 to 1008, 1920 to 1080,
                1280 to 720, 1138 to 640, 3840 to 2160)) {
                val p = IccoaAaVideoProfile.choose(hu(1600, 900), direct, auto, w, h)
                assertTrue("invalid AA mode", (p.width to p.height) in verified)
            }
    }
}
