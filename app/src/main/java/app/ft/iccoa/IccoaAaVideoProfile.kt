package app.ft.iccoa

import app.ft.aa.AaProtocol
import app.ft.projection.VideoPlan

/**
 * AA VIDEO (H.264 + input/touch) always uses one supported, complete raster:
 * 800x480, 1280x720 or 1920x1080. Toyota's physical content aspect ratio is
 * adapted only in the composited viewport; it never changes AA VIDEO margins.
 * Direct H.264 still requires equality with the negotiated WFD raster.
 */
internal object IccoaAaVideoProfile {
    data class Selection(
        val width: Int,
        val height: Int,
        val directStandard: Boolean,
        val applyToPrefs: Boolean,
        val reason: String
    )

    private fun protocolSupports(w: Int, h: Int): Boolean =
        AaProtocol.standardSize(w, h) == (w to h)

    fun choose(
        plan: VideoPlan,
        directRequested: Boolean,
        matchCar: Boolean,
        manualWidth: Int,
        manualHeight: Int
    ): Selection {
        val wfd = plan.streamWidth to plan.streamHeight
        // Old persisted custom AA sizes were UI shapes, never valid video modes.
        // Preserve an explicit 720p tier rather than silently promoting to 1080p.
        val manual = AaProtocol.standardSize(manualWidth, manualHeight)
        val legacyManual = manual != (manualWidth to manualHeight)
        if (directRequested && plan.fps in 1..30 &&
            protocolSupports(plan.streamWidth, plan.streamHeight) &&
            (matchCar || manual == wfd)) {
            return Selection(plan.streamWidth, plan.streamHeight, true,
                matchCar || legacyManual, "DIRECT_STANDARD_WFD")
        }
        if (matchCar) {
            // The full FT virtual display is NOT the actual AA viewport on the HU.
            // Select only a standard AA tier from the negotiated WFD VIDEO raster;
            // AaOverlay measures and letterboxes the visible picture separately.
            val (w, h) = IccoaAaSize.fit(plan.streamWidth, plan.streamHeight)
            return Selection(w, h, false, true,
                if (directRequested) "WFD_NONSTANDARD_FALLBACK_TO_COMPOSITION"
                else "COMPOSITED_MATCH_CAR")
        }
        return Selection(manual.first, manual.second, false, legacyManual,
            if (directRequested) "WFD_NONSTANDARD_MANUAL_FALLBACK"
            else "COMPOSITED_MANUAL")
    }
}
