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

    @Test fun genuineOEMCarlinkPreMappedCenterWithExplicitSurfaceDimensions() {
        // Direct examination of OEM Carlink.apk classes3.dex:
        // UibcManager b3.a/b maps touch 2421x1272 -> source VIDEO 1280x720
        // BEFORE JSON, but b3.sendTouchEvent uses width/height=2421x1272
        // from the local touch SurfaceView. Prior FT scaled it AGAIN.
        val frame = app.ft.projection.VideoContentTransform(
            2422, 1272, 1280, 720, true)
        assertEquals(IccoaInputCoordinateSpace.Mode.VIDEO_STREAM,
            IccoaInputCoordinateSpace.inferRawCenter(
                640.0, 360.0, 2421, 1272, frame))
        // This used to be ~338x214 and caused first OK to fail.
        val incorrect = UibcFrameMapping.toStream(
            640.0, 360.0, 2421, 1272, frame)!!
        assertEquals(338, incorrect.first, 2)
        assertEquals(214, incorrect.second, 2)
    }

    @Test fun unmodifiedSenderPixelCenterRemainsPanelSpace() {
        val frame = app.ft.projection.VideoContentTransform(
            2422, 1272, 1280, 720, true)
        assertEquals(IccoaInputCoordinateSpace.Mode.NATIVE_DISPLAY,
            IccoaInputCoordinateSpace.inferRawCenter(
                1210.5, 636.0, 2421, 1272, frame))
    }

    @Test fun wrongTapDoesNotSelectAHypothesis() {
        val frame = app.ft.projection.VideoContentTransform(
            2422, 1272, 1280, 720, true)
        assertEquals(IccoaInputCoordinateSpace.Mode.UNKNOWN,
            IccoaInputCoordinateSpace.inferRawCenter(
                115.0, 88.0, 2421, 1272, frame))
    }

    @Test fun sameSenderAndFrameWidthEitherInterpretationIsEquivalent() {
        val frame = app.ft.projection.VideoContentTransform(
            1280, 720, 1280, 720, false)
        assertEquals(IccoaInputCoordinateSpace.Mode.NATIVE_DISPLAY,
            IccoaInputCoordinateSpace.inferRawCenter(
                640.0, 360.0, 1280, 720, frame))
    }

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
