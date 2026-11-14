package app.ft.iccoa

import app.ft.projection.VideoPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class IccoaInputCoordinateSpaceTest {
    private val plan = VideoPlan(
        contentWidth = 2422, contentHeight = 1272,
        streamWidth = 1280, streamHeight = 720,
        fps = 30, bitrate = 3_555_000, preserveAspect = true
    )

    @Test fun nativePanelCenterUsesNativeCoordinateSpace() {
        assertEquals(IccoaInputCoordinateSpace.Mode.NATIVE_DISPLAY,
            IccoaInputCoordinateSpace.inferFromWelcome(1211, 636, plan))
    }

    @Test fun receiverSendingWfdStreamPixelsWithoutUnitsUsesStreamSpace() {
        // On the real centered OK, 640x360 is the WFD raster center.
        // Treating it as a 2422x1272 panel coordinate is the old misfire.
        assertEquals(IccoaInputCoordinateSpace.Mode.VIDEO_STREAM,
            IccoaInputCoordinateSpace.inferFromWelcome(640, 360, plan))
    }

    @Test fun offCenterTouchDoesNotGuessSpaceOrForceCalibration() {
        assertEquals(IccoaInputCoordinateSpace.Mode.UNKNOWN,
            IccoaInputCoordinateSpace.inferFromWelcome(120, 120, plan))
    }

    @Test fun matchingPanelAndStreamCannotInferButNeedsNoConversion() {
        val same = VideoPlan(1280, 720, 1280, 720, 30, 2_500_000)
        assertEquals(IccoaInputCoordinateSpace.Mode.UNKNOWN,
            IccoaInputCoordinateSpace.inferFromWelcome(640, 360, same))
    }

    @Test fun alternateHighResolutionStillDetectsActualSpaceWithoutOemProfiles() {
        val high = VideoPlan(2422, 1272, 1920, 1080, 30, 8_000_000,
            preserveAspect = true)
        assertEquals(IccoaInputCoordinateSpace.Mode.NATIVE_DISPLAY,
            IccoaInputCoordinateSpace.inferFromWelcome(1211, 636, high))
        assertEquals(IccoaInputCoordinateSpace.Mode.VIDEO_STREAM,
            IccoaInputCoordinateSpace.inferFromWelcome(960, 540, high))
    }
}
