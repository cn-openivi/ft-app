package app.ft.iccoa

/**
 * Redacted Android socket-bind diagnostics. The exact SocketException message may
 * contain network handles, peer addresses, or interface details: never log it.
 * Record only fixed errno/status tokens so field reports distinguish policy
 * denial (EPERM) from a bad FD (EBADF), invalid arguments or lost Network.
 */
internal object IccoaBindFailureReason {
    private val errnoCodes = listOf(
        "EPERM", "EACCES", "EBADF", "EINVAL", "ENETUNREACH",
        "ENONET", "EHOSTUNREACH", "ENODEV", "ENOTCONN"
    )

    fun classify(error: Throwable): String {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 6) {
            val message = cause.message.orEmpty().uppercase(java.util.Locale.ROOT)
            for (code in errnoCodes) {
                val offset = message.indexOf(code)
                if (offset < 0) continue
                val before = offset == 0 || !message[offset - 1].isLetterOrDigit()
                val end = offset + code.length
                val after = end == message.length || !message[end].isLetterOrDigit()
                if (before && after) return code
            }
            if (message.contains("SOCKET IS CONNECTED")) return "ALREADY_CONNECTED"
            cause = cause.cause
            depth++
        }
        return "UNKNOWN"
    }
}
