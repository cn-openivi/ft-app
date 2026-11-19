package app.ft.iccoa

/** Switching Wi-Fi handles after authentication invalidates the entire
 * RTP/RTSP/UIBC transport epoch; a fresh Quick Auth connection is safer than
 * reusing an encoder or a media listener bound to the retired interface.
 * Pre-auth network replacements may retry AUTH on B within the same attempt.
 */
internal object IccoaNetworkReplacementPolicy {
    fun mustRestartSession(networkChanged: Boolean, authenticated: Boolean): Boolean =
        networkChanged && authenticated
}
