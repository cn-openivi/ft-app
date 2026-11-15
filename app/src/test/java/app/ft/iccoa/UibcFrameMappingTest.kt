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

    @Test fun inputOutsideDeclaredWindowCannotBecomeAClickOnEdge() {
        assertNull(UibcFrameMapping.toStream(-1.0, 636.0, 2421, 1272, frame))
        assertNull(UibcFrameMapping.toStream(5000.0, 636.0, 2421, 1272, frame))
        assertNull(UibcFrameMapping.toStream(Double.NaN, 636.0, 2421, 1272, frame))
        assertNull(UibcFrameMapping.toStream(5.0, 6.0, 0, 1272, frame))
        assertNull(UibcFrameMapping.fromStream(-1.0, 360.0, 1280, 720))
        assertEquals(640 to 360,
            UibcFrameMapping.fromStream(640.0, 360.0, 1280, 720))
    }
}
