package app.ft.projection

import kotlin.math.roundToInt

/**
 * Public touch-facing wrapper around the SAME viewport/affine math used
 * by FrameGate to draw the H.264 picture. No independent aspect formulas.
 *
 * UIBC source units must be normalized BEFORE calling these methods.
 */
internal data class TouchVideoGeometry(
    val contentWidth: Int,
    val contentHeight: Int,
    val streamWidth: Int,
    val streamHeight: Int,
    val preserveAspect: Boolean
) {
    private val transform = VideoContentTransform(
        contentWidth, contentHeight, streamWidth, streamHeight, preserveAspect
    )

    fun toStream(x: Int, y: Int): Pair<Int, Int> {
        val px = x.coerceIn(0, contentWidth - 1)
        val py = y.coerceIn(0, contentHeight - 1)
        if (contentWidth == streamWidth && contentHeight == streamHeight) return px to py
        val (streamX, streamY) = transform.contentToStream(px.toDouble(), py.toDouble())
        return streamX.roundToInt().coerceIn(0, streamWidth - 1) to
            streamY.roundToInt().coerceIn(0, streamHeight - 1)
    }

    fun toContent(x: Int, y: Int): Pair<Int, Int> {
        if (contentWidth == streamWidth && contentHeight == streamHeight) return x to y
        val (contentX, contentY) = transform.streamToContent(x.toDouble(), y.toDouble())
        return contentX.toInt().coerceIn(0, contentWidth - 1) to
            contentY.toInt().coerceIn(0, contentHeight - 1)
    }

    /** A touch on a black letterbox bar must not become a button-edge click. */
    fun isInsideRenderedContent(x: Int, y: Int): Boolean =
        transform.isStreamContent(x.toDouble(), y.toDouble())
}
