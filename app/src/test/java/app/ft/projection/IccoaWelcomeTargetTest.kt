package app.ft.projection

import org.junit.Assert.*
import org.junit.Test

class IccoaWelcomeTargetTest {
    @Test fun centralOkVisiblyLargerOnPhysicalHuButNotWholeScreen() {
        assertTrue(IccoaWelcomeTarget.contains(1211, 636, 2422, 1272))
        assertTrue(IccoaWelcomeTarget.contains(1270, 636, 2422, 1272))
        assertFalse(IccoaWelcomeTarget.contains(1320, 636, 2422, 1272))
        assertTrue(IccoaWelcomeTarget.radiusPx(2422, 1272) in 80f..90f)
        assertFalse(IccoaWelcomeTarget.contains(-1, 636, 2422, 1272))
    }

    @Test fun secondTargetChecksFarSideRatherThanJustCenter() {
        val x = IccoaWelcomeTarget.rightX(2422)
        val y = IccoaWelcomeTarget.rightY(1272)
        assertTrue(IccoaWelcomeTarget.containsRightProbe(x, y, 2422, 1272))
        assertTrue(IccoaWelcomeTarget.containsRightProbe(x + 20, y - 20, 2422, 1272))
        assertFalse(IccoaWelcomeTarget.containsRightProbe(1211, 636, 2422, 1272))
        assertFalse(IccoaWelcomeTarget.containsRightProbe(x - 170, y, 2422, 1272))
        assertFalse(IccoaWelcomeTarget.contains(x, y, 2422, 1272))
    }

    @Test fun targetsScaleAcrossResolutionsAndOrientation() {
        for ((w, h) in listOf(1280 to 720, 1920 to 1080, 3840 to 2160, 720 to 1280)) {
            assertTrue(IccoaWelcomeTarget.contains(w / 2, h / 2, w, h))
            assertTrue(IccoaWelcomeTarget.containsRightProbe(
                IccoaWelcomeTarget.rightX(w), IccoaWelcomeTarget.rightY(h), w, h))
            assertFalse(IccoaWelcomeTarget.containsRightProbe(w / 2, h / 2, w, h))
        }
        assertFalse(IccoaWelcomeTarget.contains(0, 0, 0, 1272))
    }
}
