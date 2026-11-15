package app.ft.iccoa

import app.ft.projection.VideoContentTransform
import kotlin.math.roundToInt

/**
 * Convert JSON UIBC source-frame coordinates to the actual H.264 video frame
 * in ONE floating-point pass, with no intermediate integer rounding.
 *
 * Width/height in the packet describe the coordinate units IF the sender is
 * pixel-based. No unknown OEM-specific units are guessed here. The first
 * center OK remains an independent validation of that interpretation.
 */
internal object UibcFrameMapping {
    fun toStream(
        x: Double,
        y: Double,
        sourceWidth: Int,
        sourceHeight: Int,
        frame: VideoContentTransform,
        allowOutsideAfterDown: Boolean = false
    ): Pair<Int, Int>? {
        if (!x.isFinite() || !y.isFinite() ||
            sourceWidth <= 0 || sourceHeight <= 0) return null

        // An event outside its declared source geometry must not be coerced
        // onto a clickable frame edge. Accept the final inclusive pixel only.
        if (!allowOutsideAfterDown &&
            (x < 0 || y < 0 || x > sourceWidth || y > sourceHeight)) return null

        // A MOVE/UP may legitimately finish a gesture outside the viewport.
        // Clip it to release a previous DOWN rather than leaving it stuck.
        val contentX = x.coerceIn(0.0, sourceWidth.toDouble()) / sourceWidth * frame.contentWidth
        val contentY = y.coerceIn(0.0, sourceHeight.toDouble()) / sourceHeight * frame.contentHeight
        val (streamX, streamY) = frame.contentToStream(contentX, contentY)
        return streamX.roundToInt().coerceIn(0, frame.streamWidth - 1) to
            streamY.roundToInt().coerceIn(0, frame.streamHeight - 1)
    }

    /** A wire packet claiming the actual H.264 raster is interpreted directly. */
    fun fromStream(x: Double, y: Double, streamWidth: Int, streamHeight: Int,
                   allowOutsideAfterDown: Boolean = false): Pair<Int, Int>? {
        if (!x.isFinite() || !y.isFinite() ||
            streamWidth <= 0 || streamHeight <= 0 ||
            (!allowOutsideAfterDown &&
                (x < 0 || y < 0 || x > streamWidth || y > streamHeight))) return null
        return x.roundToInt().coerceIn(0, streamWidth - 1) to
            y.roundToInt().coerceIn(0, streamHeight - 1)
    }
}
