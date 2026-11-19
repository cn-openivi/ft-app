package app.ft.carplay

import app.ft.projection.VideoContentTransform
import kotlin.math.roundToInt

/**
 * FT200.6 source-only CarPlay reverse-touch geometry. Uses the SAME affine
 * math as the existing FT video pipeline; the AirPlay touch wire envelope,
 * reported HU raster and rotation remain subject to real Toyota negotiation.
 * Does not transmit touch or enable CarPlay.
 */
internal object CarPlayTouchGeometry {
    enum class Rotation { NONE, CLOCKWISE_90, HALF_TURN, CLOCKWISE_270 }
    enum class Status {
        SESSION_OR_PROFILE_UNVERIFIED, INVALID_GEOMETRY,
        OUTSIDE_DISPLAY_OR_LETTERBOX, CANDIDATE_NOT_SENT
    }

    data class Frame(
        val phoneWidth: Int,
        val phoneHeight: Int,
        val huVideoWidth: Int,
        val huVideoHeight: Int,
        val preserveAspect: Boolean,
        val rotation: Rotation
    )

    class Mapped(val status: Status, val phoneX: Int? = null, val phoneY: Int? = null) {
        override fun toString(): String = "CarPlayTouchMapping(status=$status, touch redacted)"
    }

    fun inverse(
        huX: Int, huY: Int, frame: Frame,
        authenticatedSessionAndObservedHuTouchProfile: Boolean
    ): Mapped {
        if (!authenticatedSessionAndObservedHuTouchProfile)
            return Mapped(Status.SESSION_OR_PROFILE_UNVERIFIED)
        if (frame.phoneWidth !in 1..8192 || frame.phoneHeight !in 1..8192 ||
            frame.huVideoWidth !in 1..8192 || frame.huVideoHeight !in 1..8192)
            return Mapped(Status.INVALID_GEOMETRY)
        if (huX !in 0 until frame.huVideoWidth ||
            huY !in 0 until frame.huVideoHeight)
            return Mapped(Status.OUTSIDE_DISPLAY_OR_LETTERBOX)

        val quarterTurn = frame.rotation == Rotation.CLOCKWISE_90 ||
            frame.rotation == Rotation.CLOCKWISE_270
        val displayWidth = if (quarterTurn) frame.phoneHeight else frame.phoneWidth
        val displayHeight = if (quarterTurn) frame.phoneWidth else frame.phoneHeight
        val transform = VideoContentTransform(
            displayWidth, displayHeight,
            frame.huVideoWidth, frame.huVideoHeight, frame.preserveAspect
        )
        // Reject black bars instead of clamping a press onto a nearby control.
        if (!transform.isStreamContent(huX.toDouble(), huY.toDouble()))
            return Mapped(Status.OUTSIDE_DISPLAY_OR_LETTERBOX)
        val (inverseX, inverseY) =
            transform.streamToContent(huX.toDouble(), huY.toDouble())
        val u = inverseX.roundToInt().coerceIn(0, displayWidth - 1)
        val v = inverseY.roundToInt().coerceIn(0, displayHeight - 1)
        val (x, y) = when (frame.rotation) {
            Rotation.NONE -> u to v
            Rotation.CLOCKWISE_90 -> v to (frame.phoneHeight - 1 - u)
            Rotation.HALF_TURN -> (frame.phoneWidth - 1 - u) to
                (frame.phoneHeight - 1 - v)
            Rotation.CLOCKWISE_270 -> (frame.phoneWidth - 1 - v) to u
        }
        return Mapped(Status.CANDIDATE_NOT_SENT, x, y)
    }
}
