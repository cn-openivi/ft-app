package app.ft.iccoa

/**
 * Android Auto projection is already H.264. Losing the next IDR at WFD RTP
 * does not make AA-to-FT-to-WFD re-encoding technically necessary.
 *
 * An OEM PAUSE/RESUME or RTP GOP loss is recoverable inside the compressed
 * path; throttle requests to avoid saturating the AA control channel and
 * report a stalled picture, but do not start a second AVC encoder.
 *
 * Normal FT composition remains a separate native-home/manual/verified codec
 * mismatch path. Never synthesize an IDR, change the codec or replay P frames
 * across the missing reference.
 */
internal object IccoaDirectRecoveryPolicy {
    const val POST_PAUSE_RETRY_MS = 2_500L
    /** Diagnostic warning threshold, NOT an automatic transcoding deadline. */
    const val POST_PAUSE_MAX_MS = 15_000L

    fun shouldRetryPostPause(elapsedMs: Long, sinceLastRequestMs: Long): Boolean =
        elapsedMs >= POST_PAUSE_RETRY_MS &&
            sinceLastRequestMs >= POST_PAUSE_RETRY_MS

    /** This is a stalled-recovery warning, not permission to encode again. */
    fun postPauseExpired(elapsedMs: Long): Boolean = elapsedMs >= POST_PAUSE_MAX_MS
}
