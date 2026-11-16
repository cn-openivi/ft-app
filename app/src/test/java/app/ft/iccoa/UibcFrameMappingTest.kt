package app.ft.iccoa

import app.ft.projection.VideoContentTransform
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class UibcFrameMappingTest {
    private val frame = VideoContentTransform(2422, 1272, 1280, 720, true)

    @Test fun explicitFieldCoordinateSource2421MapsCenterExactlyToVisibleCenter() {
        val p = UibcFrameMapping.toStream(1210.5, 636.0, 2421, 1272, frame)
        assertEquals(640 to 360, p)
        val (x, y) = frame.streamToContent(p!!.first.toDouble(), p.second.toDouble())
        assertEquals(1211.0, x, .01)
        assertEquals(636.0, y, .01)
    }

    @Test fun oneTransformMaintainsSubpixelPrecisionAcrossNativeAndWfdSizes() {
        val frames = listOf(
            VideoContentTransform(2422, 1272, 1280, 720, true),
            VideoContentTransform(2422, 1272, 1920, 1080, true),
            VideoContentTransform(1280, 720, 1280, 720, false),
            VideoContentTransform(1080, 2340, 720, 1280, true)
        )
        for (t in frames) {
            for ((nx, ny) in listOf(
                0.05 to 0.05, 0.15 to 0.15, 0.5 to 0.5,
                0.85 to 0.85, 0.95 to 0.95
            )) {
                val px = nx * (t.contentWidth - 1)
                val py = ny * (t.contentHeight - 1)
                val mapped = UibcFrameMapping.toStream(px, py,
                    t.contentWidth, t.contentHeight, t)!!
                val returned = t.streamToContent(mapped.first.toDouble(), mapped.second.toDouble())
                assertTrue("mapped x="+returned.first+" expected="+px,
                    abs(returned.first - px) <= 2.5)
                assertTrue("mapped y="+returned.second+" expected="+py,
                    abs(returned.second - py) <= 2.5)
            }
        }
    }

    @Test fun directAaUsesFullWfdFrameNeverCompositedFtLetterbox() {
        val fit = VideoContentTransform(2422, 1272, 1280, 720, true)
        val direct = VideoContentTransform(2422, 1272, 1280, 720, false)
        // The actual AA coded raster covers 0..720, whereas the old FT
        // viewport has 24px top/bottom bars in an intermediate 1280x720.
        val fitNearTop = UibcFrameMapping.toStream(
            1210.5, 0.0, 2421, 1272, fit)!!
        val directNearTop = UibcFrameMapping.toStream(
            1210.5, 0.0, 2421, 1272, direct)!!
        assertTrue(fitNearTop.second in 23..25)
        assertEquals(0, directNearTop.second)

        // The central OK alone cannot distinguish these two modes!
        assertEquals(640 to 360, UibcFrameMapping.toStream(
            1210.5, 636.0, 2421, 1272, fit))
        assertEquals(640 to 360, UibcFrameMapping.toStream(
            1210.5, 636.0, 2421, 1272, direct))

        val nearBottomFit = UibcFrameMapping.toStream(
            1210.5, 1272.0, 2421, 1272, fit)!!
        val nearBottomDirect = UibcFrameMapping.toStream(
            1210.5, 1272.0, 2421, 1272, direct)!!
        assertTrue(nearBottomFit.second in 695..697)
        assertEquals(719, nearBottomDirect.second)
    }

    @Test fun openHeadunitActualTouchSurfaceDimensionsMatchOurSinglePassNativeMapping() {
        // Cross-checked against public Open Headunit
        // TouchCoordinateMapperTest: inputSurface=1748x720, raw=1740,715,
        // AA video=1280x720, FILL, no margin => 1274,715.
        // Display metrics 1920x642 are NOT the touch input surface!
        val actual = VideoContentTransform(1748, 720, 1280, 720, false)
        assertEquals(1274 to 715,
            UibcFrameMapping.toStream(1740.0, 715.0, 1748, 720, actual))
        val wrong = VideoContentTransform(1920, 642, 1280, 720, false)
        assertNotEquals(1274 to 715,
            UibcFrameMapping.toStream(1740.0, 715.0, 1920, 642, wrong))
    }

    @Test fun oemPreMappedCarlinkStreamPixelsMustNotUsePanelWidthDenominator() {
        val video = VideoContentTransform(2422, 1272, 1280, 720, true)
        // REAL OEM b3 transform sends WFD x0/y0 already mapped, even though
        // the accompanying JSON width/height describe the 2421x1272 view.
        assertEquals(640 to 360,
            UibcFrameMapping.fromStream(640.0, 360.0, 1280, 720))
        assertEquals(338 to 214,
            UibcFrameMapping.toStream(640.0, 360.0, 2421, 1272, video))
        // The two routes must never be silently composed together.
    }

    @Test fun inputOutsideDeclaredWindowCannotBecomeAClickOnEdge() {
        assertNull(UibcFrameMapping.toStream(-1.0, 636.0, 2421, 1272, frame))
        assertNull(UibcFrameMapping.toStream(5000.0, 636.0, 2421, 1272, frame))
        assertNull(UibcFrameMapping.toStream(Double.NaN, 636.0, 2421, 1272, frame))
        assertNull(UibcFrameMapping.toStream(5.0, 6.0, 0, 1272, frame))
        assertNull(UibcFrameMapping.fromStream(-1.0, 360.0, 1280, 720))
        assertEquals(640 to 360,
            UibcFrameMapping.fromStream(640.0, 360.0, 1280, 720))

        // A valid gesture can leave the image before ACTION_UP; do not drop
        // that release and accidentally hold a button or a drag forever.
        val released = UibcFrameMapping.toStream(
            2600.0, 1300.0, 2421, 1272, frame,
            allowOutsideAfterDown = true
        )
        assertNotNull(released)
        assertNull(UibcFrameMapping.toStream(
            2600.0, 1300.0, 2421, 1272, frame))
        assertEquals(1279 to 360, UibcFrameMapping.fromStream(
            1400.0, 360.0, 1280, 720, allowOutsideAfterDown = true))
    }
}
