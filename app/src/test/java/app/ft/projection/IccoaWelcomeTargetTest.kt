package app.ft.projection

import org.junit.Assert.*
import org.junit.Test

class IccoaWelcomeTargetTest {
    @Test fun centerOfToyotaScreenIsAcceptedByAutomaticGeometryCheck() {
        assertTrue(IccoaWelcomeTarget.contains(1211, 636, 2422, 1272))
        assertTrue(IccoaWelcomeTarget.contains(1235, 636, 2422, 1272))
        assertFalse(IccoaWelcomeTarget.contains(1250, 636, 2422, 1272))
        assertTrue(IccoaWelcomeTarget.radiusPx(2422, 1272) in 30f..36f)
    }

    @Test fun misalignedAndOutOfBoundsCoordinatesTriggerFourPointFallback() {
        assertFalse(IccoaWelcomeTarget.contains(1500, 636, 2422, 1272))
        assertFalse(IccoaWelcomeTarget.contains(1211, 930, 2422, 1272))
        assertFalse(IccoaWelcomeTarget.contains(-1, 636, 2422, 1272))
        assertFalse(IccoaWelcomeTarget.contains(1211, 636, 0, 1272))
    }

    @Test fun checkScalesWithActualCarContentSizeNotFixedPixels() {
        assertTrue(IccoaWelcomeTarget.contains(960, 540, 1920, 1080))
        assertTrue(IccoaWelcomeTarget.contains(640, 360, 1280, 720))
        assertTrue(IccoaWelcomeTarget.contains(664, 360, 1280, 720))
        assertFalse(IccoaWelcomeTarget.contains(674, 360, 1280, 720))
        assertFalse(IccoaWelcomeTarget.contains(960, 750, 1920, 1080))
    }
}
