package app.ft.projection

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class VideoContentTransformTest {
    @Test fun chineseXa60UiContentLetterboxedInto720pMatchesShaderAndTouchInverse() {
        val t = VideoContentTransform(2422, 1272, 1280, 720, true)
        assertEquals(0.0, t.left, 0.0001)
        assertTrue(t.top > 20.0 && t.top < 26.0)
        val (sx, sy) = t.contentToStream(1211.0, 636.0)
        assertEquals(640.0, sx, 0.0001)
        assertEquals(360.0, sy, 0.0001)
        val (cx, cy) = t.streamToContent(sx, sy)
        assertEquals(1211.0, cx, 0.0001)
        assertEquals(636.0, cy, 0.0001)
        assertFalse(t.isStreamContent(640.0, 5.0)) // black top bar
        assertTrue(t.isStreamContent(640.0, 360.0))
    }

    @Test fun shaderQuadMatchesExactlyTheCanonicalFrameRectangle() {
        for (dims in listOf(
            intArrayOf(2422, 1272, 1280, 720),
            intArrayOf(2422, 1272, 1920, 1080),
            intArrayOf(2560, 720, 1280, 720),
            intArrayOf(1080, 2340, 720, 1280),
            intArrayOf(1920, 1080, 3840, 2160)
        )) {
            val t = VideoContentTransform(dims[0], dims[1], dims[2], dims[3], true)
            val quad = t.glQuad()
            assertEquals(8, quad.size)
            assertEquals(t.left, (quad[0] + 1) * dims[2] / 2.0, 0.0002)
            assertEquals(t.top, (quad[1] + 1) * dims[3] / 2.0, 0.0002)
            assertEquals(t.right, (quad[2] + 1) * dims[2] / 2.0, 0.0002)
            assertEquals(t.bottom, (quad[5] + 1) * dims[3] / 2.0, 0.0002)
            for ((x, y) in listOf(
                0.0 to 0.0, .5 to .5, .15 to .15, .85 to .85, 1.0 to 1.0
            )) {
                val (sx, sy) = t.contentToStream(x * dims[0], y * dims[1])
                val (cx, cy) = t.streamToContent(sx, sy)
                assertTrue(abs(cx - x * dims[0]) < 0.00001)
                assertTrue(abs(cy - y * dims[1]) < 0.00001)
            }
        }
    }

    @Test fun stretchModeUsesIndependentScalingNoPhantomOffsets() {
        val t = VideoContentTransform(2422, 1272, 1280, 720, false)
        assertEquals(0.0, t.left, 0.00001)
        assertEquals(0.0, t.top, 0.00001)
        assertEquals(1280.0, t.right, 0.00001)
        assertEquals(720.0, t.bottom, 0.00001)
        assertNotEquals(t.scaleX, t.scaleY, 0.0001)
    }

    @Test fun fieldSource2421VersusContent2422DoesNotCreateCenterMismatch() {
        val sourceWidth = 2421.0
        val sourceHeight = 1272.0
        val t = VideoContentTransform(2422, 1272, 1280, 720, true)
        val (streamX, streamY) = t.contentToStream(
            1210.5 * t.contentWidth / sourceWidth,
            636.0 * t.contentHeight / sourceHeight
        )
        val (cx, cy) = t.streamToContent(streamX, streamY)
        assertEquals(1211.0, cx, 0.001)
        assertEquals(636.0, cy, 0.001)
    }
}
