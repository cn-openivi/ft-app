package app.ft.projection

/**
 * Canonical affine transform for a rendered content raster and its H.264
 * output viewport. Both FrameGate's GLES quad and input/touch inverse MUST
 * read this exact transform.
 *
 * This is mathematical frame geometry, NOT a receiver-specific calibration.
 * The UIBC sender's coordinate units and the receiver's actual display window
 * must be established separately from negotiated protocol metadata.
 */
internal class VideoContentTransform(
    val contentWidth: Int,
    val contentHeight: Int,
    val streamWidth: Int,
    val streamHeight: Int,
    val preserveAspect: Boolean
) {
    init {
        require(contentWidth > 0 && contentHeight > 0 &&
            streamWidth > 0 && streamHeight > 0)
    }

    val scaleX: Double
    val scaleY: Double
    val left: Double
    val top: Double
    val right: Double
    val bottom: Double

    init {
        val sx = streamWidth.toDouble() / contentWidth
        val sy = streamHeight.toDouble() / contentHeight
        val fit = minOf(sx, sy)
        scaleX = if (preserveAspect) fit else sx
        scaleY = if (preserveAspect) fit else sy
        left = (streamWidth - contentWidth * scaleX) / 2.0
        top = (streamHeight - contentHeight * scaleY) / 2.0
        right = left + contentWidth * scaleX
        bottom = top + contentHeight * scaleY
    }

    /** The same normalized (NDC) vertex positions drawn by FrameGate. */
    fun glQuad(): FloatArray {
        val l = (left / streamWidth * 2 - 1).toFloat()
        val r = (right / streamWidth * 2 - 1).toFloat()
        val t = (top / streamHeight * 2 - 1).toFloat()
        val b = (bottom / streamHeight * 2 - 1).toFloat()
        return floatArrayOf(l, t, r, t, l, b, r, b)
    }

    /** Keep intermediate coordinates in floating point until the final input. */
    fun contentToStream(x: Double, y: Double): Pair<Double, Double> =
        left + x * scaleX to top + y * scaleY

    /** The exact affine inverse used to map video touch to FT content. */
    fun streamToContent(x: Double, y: Double): Pair<Double, Double> =
        (x - left) / scaleX to (y - top) / scaleY

    /** Black video bars are not valid application touch targets. */
    fun isStreamContent(x: Double, y: Double): Boolean =
        x >= left && x < right && y >= top && y < bottom
}
