package app.ft.iccoa

import app.ft.projection.VideoPlan
import org.junit.Assert.*
import org.junit.Test

class IccoaDirectTouchMapTest {
    private val rav = VideoPlan(
        contentWidth = 2422, contentHeight = 1272,
        streamWidth = 1920, streamHeight = 1080,
        fps = 30, bitrate = 8_000_000, preserveAspect = true
    )

    @Test fun offPreservesEveryDirectAAVideoPixelAndDoesNotReadCalibration() {
        val points = listOf(0 to 0, 1919 to 0, 0 to 1079, 1919 to 1079,
            960 to 540, 500 to 82, 1120 to 990)
        for ((x, y) in points) {
            val p = IccoaDirectTouchMap.correctedStreamPoint(x, y, rav, false) { _, _, _, _ ->
                fail("Calibration must not be evaluated when disabled")
                0 to 0
            }
            assertEquals(x to y, p)
        }
    }

    @Test fun emptyOrIdentityCarCalibrationMustNotClipDirectAAMargins() {
        for ((x, y) in listOf(0 to 0, 1919 to 1079, 960 to 540, 15 to 15)) {
            val p = IccoaDirectTouchMap.correctedStreamPoint(x, y, rav, true) { cx, cy, _, _ ->
                cx to cy
            }
            assertEquals(x to y, p)
        }
    }

    @Test fun learnedFourPointHUCorrectionMovesTheSameDirectionInDirectAA() {
        val (x, y) = IccoaDirectTouchMap.correctedStreamPoint(960, 540, rav, true) { cx, cy, _, _ ->
            (cx + 20) to (cy - 12)
        }
        assertTrue(x in 972..977)
        assertTrue(y in 529..533)
    }

    @Test fun explicitlyMatchedAAResolutionIsNotAssumedToBe1920x1080() {
        val p = VideoPlan(1280, 720, 1280, 720, 30, 3_000_000)
        val result = IccoaDirectTouchMap.correctedStreamPoint(1279, 719, p, true) { x, y, _, _ ->
            x to y
        }
        assertEquals(1279 to 719, result)
    }
}
