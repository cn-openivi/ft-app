package app.ft.iccoa

import app.ft.projection.VideoPlan

/**
 * Direct AA must use exactly the WFD H.264 raster: do not mix a custom AA
 * content height (e.g. 1008) with the 1080p access-unit contract.
 * Ordinary composited AA retains its proven car-aspect/margins behavior.
 */
internal object IccoaAaVideoProfile {
    data class Selection(
        val width: Int,
        val height: Int,
        val directStandard: Boolean,
        val applyToPrefs: Boolean,
        val reason: String
    )

    private val STANDARD = setOf(1920 to 1080, 1280 to 720, 800 to 480)

    fun choose(
        plan: VideoPlan,
        directRequested: Boolean,
        matchCar: Boolean,
        manualWidth: Int,
        manualHeight: Int
    ): Selection {
        val wfd = plan.streamWidth to plan.streamHeight
        if (directRequested && plan.fps in 1..30 && wfd in STANDARD) {
            return Selection(plan.streamWidth, plan.streamHeight, true, true, "DIRECT_STANDARD_WFD")
        }
        if (matchCar) {
            val (width, height) = IccoaAaSize.fit(plan.contentWidth, plan.contentHeight)
            return Selection(width, height, false, true,
                if (directRequested) "WFD_NONSTANDARD_FALLBACK_TO_COMPOSITION"
                else "COMPOSITED_MATCH_CAR")
        }
        return Selection(manualWidth, manualHeight, false, false,
            if (directRequested) "WFD_NONSTANDARD_MANUAL_FALLBACK"
            else "COMPOSITED_MANUAL")
    }
}
