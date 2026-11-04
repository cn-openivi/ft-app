package app.ft.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class AaViewportTest {
    @Test fun sideRailAndLetterboxAreOutsideThePicture() {
        val viewport = AaViewport(104, 50, 800, 450)
        assertFalse(viewport.contains(103, 100))
        assertFalse(viewport.contains(400, 49))
        assertFalse(viewport.contains(904, 100))
        assertTrue(viewport.contains(104, 50))
        assertTrue(viewport.contains(903, 499))
    }

    @Test fun scaledPictureMapsToTheAndroidAutoCoordinates() {
        val viewport = AaViewport(104, 50, 800, 450)
        assertEquals(0 to 0, viewport.map(104, 50, 1280, 720))
        assertEquals(640 to 360, viewport.map(504, 275, 1280, 720))
        assertEquals(640 to 360, AaViewport(0, 0, 800, 450).map(400, 225, 1280, 720))
    }

    @Test fun paddedAndCroppedFramesMapTouchProportionallyAtEveryNegotiatedSize() {
        // No universal AA frame/content pixel difference: 1008/1080
        // represents ONE negotiated aspect ratio. Real AA input geometry
        // follows the active content rectangle, not its padded H.264 frame.
        for ((active, encoded) in listOf(
            (640 to 360) to (800 to 480),
            (800 to 448) to (800 to 480),
            (1280 to 672) to (1280 to 720),
            (1920 to 1008) to (1920 to 1080),
            (1920 to 720) to (1920 to 1080),
            (1280 to 720) to (1280 to 720),
            (1920 to 1080) to (1920 to 1080)
        )) {
            val (activeW, activeH) = active
            val (frameW, frameH) = encoded
            val layout = AaVideoLayout.forFrame(activeW, activeH, frameW, frameH)
            assertEquals(activeW, layout.contentWidth)
            assertEquals(activeH, layout.contentHeight)
            // Draw the negotiated picture at a percentage of the output
            // surface, independently of H.264 padding and physical DPI.
            val left = (frameW * 0.06).roundToInt()
            val top = (frameH * 0.05).roundToInt()
            val drawnWidth = (activeW * 0.75).roundToInt()
            val drawnHeight = (activeH * 0.75).roundToInt()
            val viewport = AaViewport(left, top, drawnWidth, drawnHeight)
            for ((rx, ry) in listOf(
                0.0 to 0.0, 0.25 to 0.25, 0.50 to 0.50,
                0.75 to 0.75, 0.95 to 0.95
            )) {
                val physicalX = left + (drawnWidth * rx).roundToInt()
                val physicalY = top + (drawnHeight * ry).roundToInt()
                assertTrue(viewport.contains(physicalX, physicalY))
                val mapped = viewport.map(physicalX, physicalY, activeW, activeH)
                assertTrue("X for ${activeW}x${activeH} inside ${frameW}x${frameH}",
                    abs(mapped.first - (activeW * rx).roundToInt()) <= 2)
                assertTrue("Y for ${activeW}x${activeH} inside ${frameW}x${frameH}",
                    abs(mapped.second - (activeH * ry).roundToInt()) <= 2)
            }
            assertFalse(viewport.contains(left + drawnWidth / 2, top + drawnHeight))
            assertFalse(viewport.contains(left + drawnWidth, top + drawnHeight / 2))
        }
    }

    @Test fun largerOrSmallerPhysicalHuStillScalesToNegotiatedAaContent() {
        // AA standard video resolutions are protocol-limited; a 2K HU may
        // render an upscaled viewport, not negotiate a fictitious 2K sensor.
        for ((screen, content) in listOf(
            (640 to 360) to (800 to 448),
            (1024 to 600) to (1280 to 672),
            (2560 to 1440) to (1920 to 1008)
        )) {
            val (screenW, screenH) = screen
            val (targetW, targetH) = content
            val viewport = AaViewport(
                (screenW * 0.08).roundToInt(),
                (screenH * 0.10).roundToInt(),
                (screenW * 0.82).roundToInt(),
                (screenH * 0.80).roundToInt()
            )
            for ((rx, ry) in listOf(0.20 to 0.20, 0.50 to 0.50, 0.90 to 0.90)) {
                val x = viewport.left + (viewport.width * rx).roundToInt()
                val y = viewport.top + (viewport.height * ry).roundToInt()
                val p = viewport.map(x, y, targetW, targetH)
                assertTrue(abs(p.first - (targetW * rx).roundToInt()) <= 3)
                assertTrue(abs(p.second - (targetH * ry).roundToInt()) <= 3)
            }
        }
    }

    @Test fun draggingOutsideThePictureStaysWithinTheInputRange() {
        val viewport = AaViewport(104, 50, 800, 450)
        assertEquals(0 to 0, viewport.map(-50, -50, 1280, 720))
        assertEquals(1279 to 719, viewport.map(1000, 600, 1280, 720))
    }
}
