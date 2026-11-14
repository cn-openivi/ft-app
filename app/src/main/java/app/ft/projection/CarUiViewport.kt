package app.ft.projection

import kotlin.math.roundToInt

/**
 * Keep FT controls the same proportion of the available car viewport regardless
 * of the negotiated video pixel resolution. This is a logical layout density,
 * NOT a claim about the panel's physical PPI.
 *
 * 1280x720 is only the reference coordinate canvas, not a forced codec mode.
 * For the same aspect ratio, doubling both pixel dimensions doubles the
 * effective pixels per dp and leaves the apparent size unchanged.
 *
 * Legacy carDensity 160 is a neutral 100% zoom adjustment, while an explicitly
 * saved 240 remains a 150% user zoom. AA DPI and its H.264 path are independent.
 */
internal object CarUiViewport {
    const val REFERENCE_WIDTH = 1280f
    const val REFERENCE_HEIGHT = 720f
    const val NEUTRAL_UI_SCALE = 160

    fun logicalDpi(widthPx: Int, heightPx: Int, legacyUiScale: Int = NEUTRAL_UI_SCALE): Int {
        val adjusted = legacyUiScale.takeIf { it > 0 } ?: NEUTRAL_UI_SCALE
        if (widthPx <= 0 || heightPx <= 0) return adjusted.coerceIn(80, 640)

        val portrait = heightPx > widthPx
        val referenceWidth = if (portrait) REFERENCE_HEIGHT else REFERENCE_WIDTH
        val referenceHeight = if (portrait) REFERENCE_WIDTH else REFERENCE_HEIGHT
        val fitScale = minOf(widthPx / referenceWidth, heightPx / referenceHeight)

        // This remains a densityDpi value only because Android's virtual
        // DisplayManager API expresses logical viewport scale in that unit.
        return (adjusted * fitScale).roundToInt().coerceIn(80, 640)
    }
}
