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
/**
 * Six explicitly named relative visual zoom levels.
 *
 * Storage retains the legacy carDensity integer for seamless migrations, but
 * it is no longer presented as physical DPI. 160 means exactly 100% of the
 * baseline layout size, normalized by current virtual display dimensions.
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
        if (widthPx <= 0 || heightPx <= 0) return adjusted.coerceIn(80, 640)

        val portrait = heightPx > widthPx
        val referenceWidth = if (portrait) REFERENCE_HEIGHT else REFERENCE_WIDTH
        val referenceHeight = if (portrait) REFERENCE_WIDTH else REFERENCE_HEIGHT
        val fitScale = minOf(widthPx / referenceWidth, heightPx / referenceHeight)

        // This remains a densityDpi value only because Android's virtual
        // DisplayManager API expresses logical viewport scale in that unit.
        return (adjusted * fitScale).roundToInt().coerceIn(80, 960)
    }
}
