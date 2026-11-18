package app.ft.aa

/**
 * OEM Home/ICCOA PLAY is an Android Auto video-focus transition, not a codec
 * keyframe command. The phone owns the AVC encoder and chooses when to emit IDR.
 *
 * Keep the requested visibility per AA connection. Duplicate OEM notifications
 * must not flood focus transitions, and AA's own SETUP/START must not undo a
 * native Home focus while the ICCOA receiver is paused.
 */
internal class AaVehicleVideoFocus {
    private var projected = true

    @Synchronized fun transition(toProjected: Boolean): Int? {
        if (projected == toProjected) return null
        projected = toProjected
        return currentMode()
    }

    @Synchronized fun currentMode(): Int =
        if (projected) AaProtocol.VIDEO_FOCUS_PROJECTED else AaProtocol.VIDEO_FOCUS_NATIVE

    @Synchronized fun replyToPhoneRequest(requested: Int): Int =
        if (projected) requested else AaProtocol.VIDEO_FOCUS_NATIVE
}
