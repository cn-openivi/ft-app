package app.ft.iccoa

import app.ft.projection.TouchVideoGeometry
import app.ft.projection.VideoPlan

/**
 * Direct H.264 shows the whole AA/WFD raster, while the FT four-point HU
 * calibration was measured on the former aspect-fitted FT content surface.
 * Carry only the measured correction vector into the full video raster.
 *
 * Identity/no profile means exact unmodified WFD coordinates, including
 * pixels that used to be in the FT letterbox margin. Never invent a 1080p
 * screen or a forced margin for phones that selected a different WFD mode.
 */
internal object IccoaDirectTouchMap {
    fun correctedStreamPoint(
        rawX: Int, rawY: Int, plan: VideoPlan,
        calibrationEnabled: Boolean,
        correctContent: (Int, Int, Int, Int) -> Pair<Int, Int>
    ): Pair<Int, Int> {
        val x = rawX.coerceIn(0, plan.streamWidth - 1)
        val y = rawY.coerceIn(0, plan.streamHeight - 1)
        if (!calibrationEnabled) return x to y

        val geometry = TouchVideoGeometry(
            plan.contentWidth, plan.contentHeight,
            plan.streamWidth, plan.streamHeight, plan.preserveAspect
        )
        val (contentX, contentY) = geometry.toContent(x, y)
        val (correctedX, correctedY) = correctContent(
            contentX, contentY, plan.contentWidth, plan.contentHeight
        )
        val (nominalX, nominalY) = geometry.toStream(contentX, contentY)
        val (correctedStreamX, correctedStreamY) = geometry.toStream(
            correctedX.coerceIn(0, plan.contentWidth - 1),
            correctedY.coerceIn(0, plan.contentHeight - 1)
        )
        return (x + correctedStreamX - nominalX).coerceIn(0, plan.streamWidth - 1) to
            (y + correctedStreamY - nominalY).coerceIn(0, plan.streamHeight - 1)
    }
}
