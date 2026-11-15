package app.ft.iccoa

/**
 * Direct AA H.264 survives an OEM CONTROL5/RTSP PAUSE and resumes only on an
 * independently written RTP IDR. A temporary absence of AA IDR is NOT a
 * reason to immediately run FT's second AVC encoder.
 *
 * We probe the existing AA source a bounded number of times; if the receiver
 * never gets a decodable GOP, the legacy composited video remains the explicit
 * emergency transport. No invented IDR, duplicate codec or changed WFD mode.
 */
internal object IccoaDirectRecoveryPolicy {
    const val POST_PAUSE_RETRY_MS = 2_500L
    const val POST_PAUSE_MAX_MS = 15_000L

    fun shouldRetryPostPause(elapsedMs: Long, sinceLastRequestMs: Long): Boolean =
        elapsedMs >= POST_PAUSE_RETRY_MS &&
            elapsedMs < POST_PAUSE_MAX_MS &&
            sinceLastRequestMs >= POST_PAUSE_RETRY_MS

    fun postPauseExpired(elapsedMs: Long): Boolean = elapsedMs >= POST_PAUSE_MAX_MS
}
