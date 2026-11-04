package app.ft.aa

/**
 * Android Auto AUTH_COMPLETE is a single control response per TLS session.
 * Late/duplicate SSL_HANDSHAKE frames must not restart the AA control state.
 */
internal class AaAuthCompletionGate {
    private var sent = false

    @Synchronized fun shouldSend(handshakeDone: Boolean): Boolean {
        if (!handshakeDone || sent) return false
        sent = true
        return true
    }
}
