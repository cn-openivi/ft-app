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

    // Derive the currently implemented AA codec modes from AA protocol support.
    // This is NOT a phone-specific resolution list. The phone may still select
    // a lower mode: actual H.264 SPS must be validated before Direct admission.
    private fun protocolSupports(w: Int, h: Int): Boolean =
        app.ft.aa.AaProtocol.standardSize(w, h) == (w to h)

    fun choose(
        plan: VideoPlan,
        directRequested: Boolean,
        matchCar: Boolean,
        manualWidth: Int,
        manualHeight: Int
    ): Selection {
        val wfd = plan.streamWidth to plan.streamHeight
        // Auto derives an AA candidate from this car's negotiated WFD mode.
        // If a user explicitly selected an AA size in FT, respect that choice:
        // never silently override it to match the car's higher resolution.
        val manualMatchesWfd = matchCar ||
            (manualWidth == plan.streamWidth && manualHeight == plan.streamHeight)
        if (directRequested && plan.fps in 1..30 &&
            protocolSupports(plan.streamWidth, plan.streamHeight) && manualMatchesWfd) {
            return Selection(plan.streamWidth, plan.streamHeight, true, matchCar,
                "DIRECT_STANDARD_WFD")
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
