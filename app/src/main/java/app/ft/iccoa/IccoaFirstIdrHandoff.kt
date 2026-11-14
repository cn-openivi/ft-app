package app.ft.iccoa

/**
 * Immutable handoff of the very first AA IDR from the socket/decoder callback
 * to Android's UI Handler. The original buffer cannot be retained by reference.
 * No video bytes are logged; one bounded allocation per Direct attempt.
 */
internal object IccoaFirstIdrHandoff {
    internal data class Frame(
        val bytes: ByteArray,
        val timestamp: Long,
        val config: ByteArray
    )

    fun capture(bytes: ByteArray, timestamp: Long, config: ByteArray): Frame? {
        if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024 ||
            !IccoaDirectAvc.isIdr(bytes) || config.isEmpty()) return null
        return Frame(bytes.copyOf(), timestamp, config)
    }

    /** A new AA config/epoch invalidates this frame even if raster is identical. */
    fun configurationStillCurrent(frame: Frame, config: ByteArray?): Boolean =
        config === frame.config
}
