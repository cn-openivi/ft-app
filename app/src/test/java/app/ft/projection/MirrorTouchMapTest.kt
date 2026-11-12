package app.ft.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class MirrorTouchMapTest {
    @Test fun normalizedCalibrationCoordinatesMapExactlyOnceToPhonePixels() {
        assertEquals(0f to 0f, MirrorTouchMap.normalizedPhonePoint(0, 0, 2400, 1080))
        assertEquals(1200f to 540f, MirrorTouchMap.normalizedPhonePoint(500, 500, 2400, 1080))
        assertEquals(2397.6f, MirrorTouchMap.normalizedPhonePoint(999, 999, 2400, 1080)!!.first, 0.01f)
        assertNull(MirrorTouchMap.normalizedPhonePoint(500, 500, 0, 1080))
    }

    @Test fun portraitCenterAndCornersUseActualScaledOutput() {
        val map = MirrorTouchMap(2422, 1272, 586, 1272, 1080, 2340)
        val center = map.map(1211, 636)!!
        assertEquals(540f, center.first, 1.5f)
        assertEquals(1170f, center.second, 1.5f)
        assertNull(map.map(500, 636))
        assertNull(map.map(1900, 636))
        val top = map.map(1211, 0)!!
        val bottom = map.map(1211, 1271)!!
        assertEquals(0f, top.second, 1f)
        assertEquals(2338f, bottom.second, 3f)
    }

    @Test fun landscapeLetterboxAndRotationRecomputesGeometry() {
        val landscape = MirrorTouchMap(2422, 1272, 2340, 1080, 2340, 1080)
        val center = landscape.map(1211, 636)!!
        assertEquals(1170f, center.first, 1f)
        assertEquals(540f, center.second, 1f)
        assertNull(landscape.map(1211, 30))
        val portrait = MirrorTouchMap(2422, 1272, 586, 1272, 1080, 2340)
        assertNotNull(portrait.map(1211, 30))
        assertNull(portrait.map(600, 636))
    }

    @Test fun releaseOutsideImageIsClampedInsteadOfDroppingGesture() {
        val map = MirrorTouchMap(2422, 1272, 586, 1272, 1080, 2340)
        assertNull(map.map(2300, 640))
        val move = map.map(2300, 640, clipToBounds = true)!!
        assertEquals(1079f, move.first, 0.1f)
        assertEquals(1177f, move.second, 3f)
    }

    @Test fun finishedRotationUsesEachOrientationsRealVisibleRectangle() {
        val carWidth = 2422
        val carHeight = 1272
        // Source and output must be from the same already-stable frame geometry,
        // not the phone orientation that was active before rotation.
        val portrait = MirrorTouchMap(carWidth, carHeight, 586, 1272, 1080, 2340)
        val landscape = MirrorTouchMap(carWidth, carHeight, 2422, 1118, 2340, 1080)
        val portraitLeft = (carWidth - 586) / 2
        val landscapeTop = (carHeight - 1118) / 2
        for (ratio in listOf(0.15, 0.5, 0.85)) {
            val p = portrait.map(
                portraitLeft + (586 * ratio).toInt(),
                (1272 * ratio).toInt()
            )!!
            assertEquals((1080 * ratio).toFloat(), p.first, 3f)
            assertEquals((2340 * ratio).toFloat(), p.second, 3f)
            val l = landscape.map(
                (2422 * ratio).toInt(),
                landscapeTop + (1118 * ratio).toInt()
            )!!
            assertEquals((2340 * ratio).toFloat(), l.first, 3f)
            assertEquals((1080 * ratio).toFloat(), l.second, 3f)
        }
        assertNull(portrait.map(portraitLeft - 1, 600))
        assertNull(landscape.map(1200, landscapeTop - 1))
    }

    @Test fun uninitializedCaptureDoesNotInventCoordinates() {
        assertNull(MirrorTouchMap(2422, 1272, 0, 0, 1080, 2340).map(1200, 600))
    }
}
