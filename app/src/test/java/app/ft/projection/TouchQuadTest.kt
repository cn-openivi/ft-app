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

    @Test fun calibrationTargetsUseScreenFractionsButMeasuredErrorsRemainPixels() {
        fun px(ratio: Double, dimension: Int) = (ratio * dimension).roundToInt()
        // Independent screens can have DIFFERENT measured physical offsets.
        // Never transform an 8px measured error into 8/480*h for another HU.
        data class Fixture(val width: Int, val height: Int, val dxPx: Int, val dyPx: Int)
        val fixtures = listOf(
            Fixture(800, 480, -6, 7),
            Fixture(1280, 720, 9, -13),
            Fixture(1920, 1008, -12, 36),
            Fixture(2400, 1200, 5, 36),
            Fixture(2560, 1440, -22, -26)
        )
        for ((w, h, dx, dy) in fixtures) {
            // These are the REAL UI positions: 15%/85% of the current
            // canvas width and height, not hard-coded coordinates.
            val desired = listOf(
                0.15 to 0.15, 0.85 to 0.15,
                0.15 to 0.85, 0.85 to 0.85
            )
            val measuredPixels = desired.map { (rx, ry) ->
                TouchPoint((px(rx, w) + dx).toDouble(), (px(ry, h) + dy).toDouble())
            }
            val quad = TouchQuad.fromSamples(measuredPixels, w, h)!!
            val savedPixelCoordinates = quad.serialize()
            // Persist the RAW measured pixel points, not percentages.
            assertEquals(measuredPixels.joinToString(",") { "${it.x},${it.y}" },
                savedPixelCoordinates)
            val restored = TouchQuad.deserialize(savedPixelCoordinates, w, h)!!
            for ((rx, ry) in desired + listOf(0.50 to 0.50, 0.50 to 0.93)) {
                val expectedX = px(rx, w)
                val expectedY = px(ry, h)
                val corrected = restored.map(expectedX + dx, expectedY + dy, w, h)
                assertTrue("pixel-corrected X ${w}x${h}, offset=${dx}px",
                    abs(corrected.first - expectedX) <= 2)
                assertTrue("pixel-corrected Y ${w}x${h}, offset=${dy}px",
                    abs(corrected.second - expectedY) <= 2)
            }
        }
    }

    @Test fun aPixelOffsetDoesNotScaleWithViewportSize() {
        // Separate profile per resolution: calibration for 800x480 must
        // not become a percentage-based 21px offset at 1440px height.
        val originalYErrorPx = 7
        val newHuYErrorPx = 29
        assertEquals(7, originalYErrorPx)
        assertEquals(29, newHuYErrorPx)
        assertTrue(newHuYErrorPx !=
            (originalYErrorPx.toDouble() * 1440 / 480).roundToInt())
        // Legacy 2-point pixel-offset model also retains pixel semantics.
        assertEquals(403, TouchScale(1.0, 3.0).map(400, 1280))
        assertEquals(403, TouchScale(1.0, 3.0).map(400, 2560))
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
