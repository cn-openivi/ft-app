package app.ft.iccoa

import app.ft.projection.IccoaWelcomeTarget
import app.ft.projection.TouchVideoGeometry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression of the actual field geometry:
 * explicit source 2421x1272, negotiated content 2422x1272, WFD 1280x720.
 * One pixel of source width mismatch cannot explain a center-OK miss if the
 * sender's x0/y0 units are physical pixels, as XiaoDroidLink does.
 *
 * This is a source-level maths proof, NOT confirmation of live UIBC units.
 */
class IccoaXiaoStyleTouchGeometryTest {
    private val native = IccoaVideoConfig("127.0.0.1", 2422, 1272)
    private val raster = TouchVideoGeometry(2422, 1272, 1280, 720, true)

    @Test fun onePixelFieldWidthDifferenceMustStillValidateCenter() {
        val raw = JSONObject("""{
          "type":1,"action":0,"width":2421,"height":1272,
          "x0":1210.5,"y0":636.0
        }""")
        val normalized = IccoaInputEvent.touch(raw, native)!!
        val (streamX, streamY) = raster.toStream(normalized.x, normalized.y)
        val (contentX, contentY) = raster.toContent(streamX, streamY)
        assertTrue(IccoaWelcomeTarget.contains(contentX, contentY, 2422, 1272))
        assertTrue(abs(contentX - 1211) <= 2)
        assertTrue(abs(contentY - 636) <= 2)
    }

    @Test fun allRepresentativePointsPreserveNativeContentCoordinateFrame() {
        for ((x, y) in listOf(
            0 to 0, 1211 to 636, 605 to 318,
            1816 to 318, 605 to 954, 1816 to 954, 2421 to 1271
        )) {
            val json = JSONObject().put("type", 1).put("action", 0)
                .put("width", 2421).put("height", 1272)
                .put("x0", x * 2421.0 / 2422.0).put("y0", y)
            val touch = IccoaInputEvent.touch(json, native)!!
            val (cx, cy) = raster.toContent(*raster.toStream(touch.x, touch.y).let {
                arrayOf(it.first, it.second)
            })
            assertTrue("x expected=$x mapped=$cx", abs(cx - x) <= 3)
            assertTrue("y expected=$y mapped=$cy", abs(cy - y) <= 3)
        }
    }
}
