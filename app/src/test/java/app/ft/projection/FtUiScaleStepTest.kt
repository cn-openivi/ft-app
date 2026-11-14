package app.ft.projection

import org.junit.Assert.*
import org.junit.Test

class FtUiScaleStepTest {
    @Test fun exactlySixNamedRelativeSizes() {
        assertEquals(listOf(70, 85, 100, 115, 135, 160),
            FtUiScaleStep.entries.map { it.percent })
        assertEquals(FtUiScaleStep.NORMAL, FtUiScaleStep.fromLegacy(160))
        assertEquals(FtUiScaleStep.VERY_LARGE, FtUiScaleStep.fromLegacy(220))
        assertEquals(FtUiScaleStep.EXTRA_LARGE, FtUiScaleStep.fromLegacy(320))
    }

    @Test fun physicalFractionOfHeightIsStableAcrossVideoResolutions() {
        for (tier in FtUiScaleStep.entries) {
            val (w1, h1) = 1280 to 720
            val (w2, h2) = 3840 to 2160
            val logical1 = CarUiViewport.logicalDpi(w1, h1, tier.storedRelativeScale)
            val logical2 = CarUiViewport.logicalDpi(w2, h2, tier.storedRelativeScale)
            val iconFraction1 = 72f * logical1 / 160f / h1
            val iconFraction2 = 72f * logical2 / 160f / h2
            assertEquals(tier.name, iconFraction1, iconFraction2, 0.001f)
        }
    }

    @Test fun legacyUserPreferenceAlwaysMapsToOneOfSixLevels() {
        for (stored in listOf(0, 80, 120, 136, 160, 183, 216, 240, 320, 640)) {
            val t = FtUiScaleStep.fromLegacy(stored)
            assertTrue(t in FtUiScaleStep.entries)
        }
    }
}
