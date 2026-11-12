package app.ft.projection

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Golden deterministic fixture: FAW RAV4 WFD native and capped AVC stream. */
class TouchVideoGeometryTest {
    @Test fun nativeRav4AspectFitHasCorrectMarginAndRoundTrip() {
        val g = TouchVideoGeometry(2422, 1272, 1920, 1080, true)
        assertEquals(36, g.toStream(0, 0).second)
        assertEquals(540, g.toStream(1211, 636).second)
        for (x in listOf(0, 300, 605, 1211, 1816, 2121, 2421)) {
            for (y in listOf(0, 159, 318, 636, 954, 1113, 1271)) {
                val (sx, sy) = g.toStream(x, y)
                val (rx, ry) = g.toContent(sx, sy)
                assertTrue("x $x roundtrip=$rx", abs(x - rx) <= 2)
                assertTrue("y $y roundtrip=$ry", abs(y - ry) <= 2)
            }
        }
    }

    @Test fun matchedSizeIsIdentityAndLetterboxClampsMargins() {
        val same = TouchVideoGeometry(1280, 720, 1280, 720, false)
        assertEquals(123 to 456, same.toStream(123, 456))
        assertEquals(123 to 456, same.toContent(123, 456))
        val g = TouchVideoGeometry(2422, 1272, 1920, 1080, true)
        assertEquals(0, g.toContent(0, 0).second)
        assertEquals(1271, g.toContent(0, 1079).second)
    }

    @Test fun aspectDisabledKeepsOriginalStretchPolicy() {
        val g = TouchVideoGeometry(1600, 900, 800, 480, false)
        assertEquals(400 to 240, g.toStream(800, 450))
        assertEquals(800 to 450, g.toContent(400, 240))
    }
}
