package app.ft.projection

import kotlin.math.roundToInt

/**
 * The same dimensions and preserveAspect policy used by FrameGate.
 * UIBC enters in content space and CarDisplay takes the exact inverse.
 * The OEM UIBC unit convention still requires on-HU verification.
 */
internal data class TouchVideoGeometry(
    val contentWidth: Int,
    val contentHeight: Int,
    val streamWidth: Int,
    val streamHeight: Int,
    val preserveAspect: Boolean
) {
    init {
        require(contentWidth > 0 && contentHeight > 0 && streamWidth > 0 && streamHeight > 0)
    }
    private val scale = if (preserveAspect) {
        minOf(streamWidth.toDouble() / contentWidth, streamHeight.toDouble() / contentHeight)
    } else 1.0
    private val left = if (preserveAspect) (streamWidth - contentWidth * scale) / 2.0 else 0.0
    private val top = if (preserveAspect) (streamHeight - contentHeight * scale) / 2.0 else 0.0

    /** Native presentation coordinates -> H264 stream/UICB coordinates. */
    fun toStream(x: Int, y: Int): Pair<Int, Int> {
        val px = x.coerceIn(0, contentWidth - 1)
        val py = y.coerceIn(0, contentHeight - 1)
        if (contentWidth == streamWidth && contentHeight == streamHeight) return px to py
        return if (preserveAspect) {
            (left + px * scale).roundToInt().coerceIn(0, streamWidth - 1) to
                (top + py * scale).roundToInt().coerceIn(0, streamHeight - 1)
        } else {
            (px.toLong() * streamWidth / contentWidth).toInt().coerceIn(0, streamWidth - 1) to
                (py.toLong() * streamHeight / contentHeight).toInt().coerceIn(0, streamHeight - 1)
        }
    }

    /** H264 stream/UICB coordinates -> original presentation content. */
    fun toContent(x: Int, y: Int): Pair<Int, Int> {
        if (contentWidth == streamWidth && contentHeight == streamHeight) return x to y
        return if (preserveAspect) {
            ((x - left) / scale).toInt().coerceIn(0, contentWidth - 1) to
                ((y - top) / scale).toInt().coerceIn(0, contentHeight - 1)
        } else {
            (x.toLong() * contentWidth / streamWidth).toInt() to
                (y.toLong() * contentHeight / streamHeight).toInt()
        }
    }
}
