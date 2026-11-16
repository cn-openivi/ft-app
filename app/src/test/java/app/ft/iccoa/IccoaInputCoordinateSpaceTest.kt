package app.ft.iccoa

import app.ft.projection.VideoPlan
import org.junit.Assert.*
import org.junit.Test

class IccoaInputCoordinateSpaceTest {
    private val plan = VideoPlan(
        contentWidth = 2422, contentHeight = 1272,
        streamWidth = 1280, streamHeight = 720,
        fps = 30, bitrate = 3_555_000, preserveAspect = true
    )

    @Test fun centreOnlyCandidateNeverCountsAsRememberedVerifiedMode() {
        val gate = UibcCoordinateModeCandidate()
        val carA = "verified_" + "a".repeat(64) + ":uibc-domain-v187"
        val carB = "verified_" + "b".repeat(64) + ":uibc-domain-v187"
        assertFalse(gate.stage(carA, IccoaInputCoordinateSpace.Mode.UNKNOWN))
        assertEquals(IccoaInputCoordinateSpace.Mode.UNKNOWN, gate.forReceiver(carA))
        assertTrue(gate.stage(carA, IccoaInputCoordinateSpace.Mode.VIDEO_STREAM))
        assertEquals(IccoaInputCoordinateSpace.Mode.VIDEO_STREAM, gate.forReceiver(carA))
        // A different authenticated HU never inherits another receiver's
        // centre-only guess, even if both stream video at 1280x720.
        assertEquals(IccoaInputCoordinateSpace.Mode.UNKNOWN, gate.forReceiver(carB))
        gate.clear() // disconnect / new geometry / failed spatial check
        assertEquals(IccoaInputCoordinateSpace.Mode.UNKNOWN, gate.forReceiver(carA))
    }

    @Test fun twoDifferentValidCoordinateModesCanBeReprobedWithoutPersistingGuess() {
        val gate = UibcCoordinateModeCandidate()
        val receiver = "verified_" + "c".repeat(64) + ":uibc-domain-v187"
        assertTrue(gate.stage(receiver, IccoaInputCoordinateSpace.Mode.NATIVE_DISPLAY))
        assertEquals(IccoaInputCoordinateSpace.Mode.NATIVE_DISPLAY,
            gate.forReceiver(receiver))
        gate.clear()
        assertTrue(gate.stage(receiver, IccoaInputCoordinateSpace.Mode.VIDEO_STREAM))
        assertEquals(IccoaInputCoordinateSpace.Mode.VIDEO_STREAM,
            gate.forReceiver(receiver))
    }

    @Test fun oldWelcomeFlagCannotSkipUnverifiedCoordinateDomainOnReconnect() {
        // Regression: FT186 persisted welcome=true, but FT187's VIDEO_STREAM
        // interpretation lived only in the socket and reset on reconnect.
        assertEquals(true, IccoaInputCoordinateSpace.requiresWelcome(
            previouslyVerified = true, knownMode = IccoaInputCoordinateSpace.Mode.UNKNOWN))
        assertEquals(false, IccoaInputCoordinateSpace.requiresWelcome(
            previouslyVerified = true, knownMode = IccoaInputCoordinateSpace.Mode.VIDEO_STREAM))
        assertEquals(false, IccoaInputCoordinateSpace.requiresWelcome(
            previouslyVerified = true, knownMode = IccoaInputCoordinateSpace.Mode.NATIVE_DISPLAY))
        assertEquals(true, IccoaInputCoordinateSpace.requiresWelcome(
            previouslyVerified = false, knownMode = IccoaInputCoordinateSpace.Mode.VIDEO_STREAM))
    }

    @Test fun persistedReceiverDomainRejectsInvalidOrObsoleteSettings() {
        assertEquals(IccoaInputCoordinateSpace.Mode.VIDEO_STREAM,
            IccoaInputCoordinateSpace.parseSaved("VIDEO_STREAM"))
        assertEquals(IccoaInputCoordinateSpace.Mode.NATIVE_DISPLAY,
            IccoaInputCoordinateSpace.parseSaved("NATIVE_DISPLAY"))
        for (v in listOf<String?>(null, "", "UNKNOWN", "TOYOTA", "RAV4", "HIGH",
            "VIDEO_STREAM;DROP TABLE", "NATIVE_DISPLAY ")) {
            assertEquals(IccoaInputCoordinateSpace.Mode.UNKNOWN,
                IccoaInputCoordinateSpace.parseSaved(v))
        }
    }

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
        assertEquals(338, incorrect.first)
        assertEquals(214, incorrect.second)
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
