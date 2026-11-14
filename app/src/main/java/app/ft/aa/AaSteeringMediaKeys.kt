package app.ft.aa

/**
 * Routing policy for media keys received over CarLife or ICCOA.
 * Android Auto owns a media key while its screen is shown, or while its
 * playback service reports playing and FT's own player is not playing.
 *
 * A routing decision does not prove the car sends steering-wheel events:
 * the transport must actually deliver them to FT.
 */
internal object AaSteeringMediaKeys {
    private const val PLAY_PAUSE = 85
    private const val NEXT = 87
    private const val PREVIOUS = 88
    private const val PLAY = 126
    private const val PAUSE = 127

    /** Preserve already-standard Android codes; only normalize known OEM variants. */
    fun normalize(rawCode: Int, swapTrackKeys: Boolean): Int = when (rawCode) {
        15 -> if (swapTrackKeys) NEXT else PREVIOUS
        16 -> if (swapTrackKeys) PREVIOUS else NEXT
        14, 79 -> PLAY_PAUSE
        else -> rawCode
    }

    fun isMedia(code: Int): Boolean =
        code == PLAY_PAUSE || code == NEXT || code == PREVIOUS ||
            code == PLAY || code == PAUSE

    fun routeToAa(
        code: Int,
        aaVisible: Boolean,
        aaConnected: Boolean,
        aaPlaying: Boolean,
        ftPlaying: Boolean
    ): Boolean = isMedia(code) &&
        (aaVisible || (aaConnected && aaPlaying && !ftPlaying))
}
