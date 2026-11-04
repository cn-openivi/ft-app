package app.ft.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class TouchQuadTest {
    private val width = 2400
    private val height = 1200

    @Test fun identityTargetsRemainExactIncludingVerticalCorners() {
        val points = listOf(
            TouchPoint(360.0, 180.0), TouchPoint(2040.0, 180.0),
            TouchPoint(360.0, 1020.0), TouchPoint(2040.0, 1020.0)
        )
        val quad = TouchQuad.fromSamples(points, width, height)!!
        val targets = listOf(360 to 180, 2040 to 180, 360 to 1020, 2040 to 1020)
        points.zip(targets).forEach { (input, target) ->
            assertEquals(target, quad.map(input.x.toInt(), input.y.toInt(), width, height))
        }
        assertEquals(1200 to 600, quad.map(1200, 600, width, height))
    }

    @Test fun independentXYDriftAndShearCorrectOnAllFourTargets() {
        // Touches arrive too far LEFT and UP, with a little top/bottom skew.
        val raw = listOf(
            TouchPoint(290.0, 130.0), TouchPoint(1650.0, 143.0),
            TouchPoint(275.0, 803.0), TouchPoint(1670.0, 816.0)
        )
        val quad = TouchQuad.fromSamples(raw, width, height)!!
        val targets = listOf(360 to 180, 2040 to 180, 360 to 1020, 2040 to 1020)
        raw.zip(targets).forEach { (p, expected) ->
            assertEquals(expected, quad.map(p.x.toInt(), p.y.toInt(), width, height))
        }
        val midpoint = quad.map(971, 473, width, height)
        assertTrue(kotlin.math.abs(1200 - midpoint.first) <= 55)
        assertTrue(kotlin.math.abs(600 - midpoint.second) <= 55)
        val persisted = TouchQuad.deserialize(quad.serialize(), width, height)
        assertNotNull(persisted)
        assertEquals(quad.map(400, 400, width, height), persisted!!.map(400, 400, width, height))
    }

    @Test fun proportionalVerticalShiftAndBottomRowsSurviveChangingHuResolution() {
        // Real Xiaomi reporter had NOT yet performed four-point calibration.
        // Represent the earlier 36/1200 vertical offset as a measured ratio,
        // NOT a universal 36px constant or a hardware-specific screen size.
        val correctionRatio = 0.03
        val sourceWidth = width
        val sourceHeight = height
        fun px(ratio: Double, dimension: Int) = (ratio * dimension).roundToInt()
        val points = listOf(
            TouchPoint(px(0.15, sourceWidth).toDouble(), px(0.15 + correctionRatio, sourceHeight).toDouble()),
            TouchPoint(px(0.85, sourceWidth).toDouble(), px(0.15 + correctionRatio, sourceHeight).toDouble()),
            TouchPoint(px(0.15, sourceWidth).toDouble(), px(0.85 + correctionRatio, sourceHeight).toDouble()),
            TouchPoint(px(0.85, sourceWidth).toDouble(), px(0.85 + correctionRatio, sourceHeight).toDouble())
        )
        val trained = TouchQuad.fromSamples(points, sourceWidth, sourceHeight)!!
        val fractions = trained.serializeNormalized(sourceWidth, sourceHeight)

        // Small, medium, large, and non-16:9 HU viewports. A calibration
        // persists as x/width, y/height and is rebuilt for each resolution.
        for ((w, h) in listOf(
            800 to 480, 1280 to 720, 1920 to 1008,
            2400 to 1200, 2560 to 1440
        )) {
            val restored = TouchQuad.deserializeNormalized(fractions, w, h)!!
            for ((rx, ry) in listOf(
                0.15 to 0.15, 0.85 to 0.15,
                0.15 to 0.85, 0.85 to 0.85,
                0.50 to 0.50, 0.50 to 0.93
            )) {
                val expectedX = px(rx, w)
                val expectedY = px(ry, h)
                val rawY = px(ry + correctionRatio, h)
                val mapped = restored.map(expectedX, rawY, w, h)
                assertTrue("x at ${w}x${h} ($rx,$ry): ${mapped.first}",
                    abs(mapped.first - expectedX) <= 2)
                assertTrue("y at ${w}x${h} ($rx,$ry): ${mapped.second}",
                    abs(mapped.second - expectedY) <= 2)
            }
        }
    }

    @Test fun malformedProportionalCalibrationsAreRejectedWithoutAFalseScale() {
        assertNull(TouchQuad.deserializeNormalized("bad", 1280, 720))
        assertNull(TouchQuad.deserializeNormalized("0.15,0.15,0.85,0.15,0.15,0.85,1.2,0.85", 1280, 720))
        assertNull(TouchQuad.deserializeNormalized("0.15,0.15,0.85,0.15,0.15,0.85,0.85,NaN", 1280, 720))
        assertNull(TouchQuad.deserializeNormalized("0.15,0.15,0.85,0.15,0.15,0.85,0.85,0.85", 0, 720))
    }

    @Test fun cannotSaveOverlappingReversedOrCorruptedPoints() {
        assertNull(TouchQuad.fromSamples(listOf(
            TouchPoint(300.0, 200.0), TouchPoint(310.0, 200.0),
            TouchPoint(300.0, 900.0), TouchPoint(310.0, 900.0)
        ), width, height))
        assertNull(TouchQuad.fromSamples(listOf(
            TouchPoint(2100.0, 180.0), TouchPoint(360.0, 180.0),
            TouchPoint(2100.0, 1020.0), TouchPoint(360.0, 1020.0)
        ), width, height))
        assertNull(TouchQuad.deserialize("invalid data", width, height))
        assertNull(TouchQuad.deserialize("1,2,3,4,5,6,Infinity,8", width, height))
    }
}
