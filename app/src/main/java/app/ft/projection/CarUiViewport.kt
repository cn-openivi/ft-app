package app.ft.projection

import kotlin.math.roundToInt

/**
 * Keep FT controls the same proportion of the available car viewport regardless
 * of the negotiated video pixel resolution. This is a logical layout density,
 * NOT a claim about the panel's physical PPI.
 *
 * Restore the upstream 480dp-high virtual viewport density. At 1280x720,
 * 160 stored user scale means NORMAL 100% and 240 effective display DPI,
 * rather than physically rendering 160dpi and shrinking all FT controls.
 * The viewport pixel size is negotiated; no codec resolution is forced.
 * AA DPI, ICCOA transport, and touch geometry remain independent.
 */
/**
 * Six explicitly named relative visual zoom levels.
 *
 * Storage retains the legacy carDensity integer for seamless migrations, but
 * it is no longer presented as physical DPI. 160 means exactly 100% of the
 * original 480dp-height layout size, normalized by current viewport dimensions.
 */
internal enum class FtUiScaleStep(val storedRelativeScale: Int, val percent: Int) {
    VERY_SMALL(112, 70),
    SMALL(136, 85),
    NORMAL(160, 100),
    LARGE(184, 115),
    VERY_LARGE(216, 135),
    EXTRA_LARGE(256, 160);

    companion object {
        fun fromLegacy(value: Int): FtUiScaleStep =
            entries.minByOrNull { kotlin.math.abs(it.storedRelativeScale - value) } ?: NORMAL
    }
}

internal object CarUiViewport {
    const val REFERENCE_WIDTH = 1280f
    const val REFERENCE_HEIGHT = 720f
    const val NEUTRAL_UI_SCALE = 160

    fun logicalDpi(widthPx: Int, heightPx: Int, legacyUiScale: Int = NEUTRAL_UI_SCALE): Int {
        val adjusted = legacyUiScale.takeIf { it > 0 } ?: NEUTRAL_UI_SCALE
        if (widthPx <= 0 || heightPx <= 0) return adjusted.coerceIn(80, 1280)

        // Mykola's original effective density was based on a 480dp high
        // virtual display, not on the stored 160 preference as an absolute DPI.
        // Use the shorter viewport side for equal sizing in either orientation.
        // The relative FT preset is a multiplier: 160 == original 100% size.
        val viewportHeightPx = minOf(widthPx, heightPx)
        val originalScale = viewportHeightPx / 480f

        // Android requires a logical densityDpi for its VirtualDisplay API.
        // 1280x720 at the NORMAL (160) preset must create a 240dpi display,
        // as it did upstream. The cap keeps all six tiers proportional at UHD.
        return (adjusted * originalScale).roundToInt().coerceIn(80, 1280)
    }
}
