package app.ft.projection

import kotlin.math.roundToInt

/**
 * Keep FT controls the same proportion of the available car viewport regardless
 * of the negotiated video pixel resolution. This is a logical layout density,
 * NOT a claim about the panel's physical PPI.
 *
 * Use a generic 1280px short-side layout baseline. Field-tested HU content
 * 2422x1272 with a legacy NORMAL=160 must not become 424dpi; it stays near
 * the original ~160dpi, with six relative zoom presets.
 * The viewport pixel size is negotiated; no codec resolution is forced.
 * AA DPI, ICCOA transport, and touch geometry remain independent.
 */
/**
 * Six explicitly named relative visual zoom levels.
 *
 * Storage retains the legacy carDensity integer for seamless migrations, but
 * it is no longer presented as physical DPI. 160 is the neutral baseline, normalized by current viewport dimensions.
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

        // The measured FT187 field UI was 2.65x too large at 424dpi.
        // Use shorter-side scaling to preserve visual proportions at different
        // source sizes while restoring historical default size at high DPI.
        // Generic 1280px short-side design reference, NOT a Toyota model
        // override. Real owner field: 2422x1272 with carDensity=160 was
        // formerly rendered at 160dpi; FT187's 480dp-high rule gave 424dpi.
        val viewportHeightPx = minOf(widthPx, heightPx)
        val originalScale = viewportHeightPx / 1280f

        // Android requires a logical densityDpi for its VirtualDisplay API.
        // The reference is resolution-independent and does not touch WFD/AA codecs.
        // Preserve six distinct selectable zoom levels even at 720p. The
        // previous 80dpi floor collapsed 70% and 85% presets into one size.
        return (adjusted * originalScale).roundToInt().coerceIn(40, 1280)
    }
}
