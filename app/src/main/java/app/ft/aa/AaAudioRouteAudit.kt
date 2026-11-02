package app.ft.aa

/**
 * Privacy-preserving diagnostic of audio arriving FROM Android Auto.
 *
 * NAVIGATION is route/turn metadata, not voice PCM. Audio can arrive only on
 * MEDIA/SPEECH/SYSTEM channel payloads. Packet/byte counts cannot prove that
 * a navigation app uses a particular Android AudioAttributes.USAGE_*.
 */
internal class AaAudioRouteAudit {
    // PCM delivery is a hot path. Atomic primitive counters avoid a
    // monitor acquisition per packet and allocate nothing per update.
    // This adds no thread, timer, buffer, or PCM copy.
    private val navigationMessages = java.util.concurrent.atomic.AtomicLong()
    private val packets = java.util.concurrent.atomic.AtomicLongArray(3)
    private val bytes = java.util.concurrent.atomic.AtomicLongArray(3)

    fun navigationMessage() {
        navigationMessages.incrementAndGet()
    }

    fun pcm(channel: Int, byteCount: Int) {
        if (byteCount <= 0) return
        val i = when (channel) {
            AaProtocol.CH_MEDIA_AUDIO -> 0
            AaProtocol.CH_SPEECH_AUDIO -> 1
            AaProtocol.CH_SYSTEM_AUDIO -> 2
            else -> return
        }
        packets.incrementAndGet(i)
        bytes.addAndGet(i, byteCount.toLong())
    }

    // Read only on session shutdown, never per PCM frame.
    fun summary(): String =
        "navigationMetadata=${navigationMessages.get()} " +
            "mediaPackets=${packets.get(0)} mediaBytes=${bytes.get(0)} " +
            "speechPackets=${packets.get(1)} speechBytes=${bytes.get(1)} " +
            "systemPackets=${packets.get(2)} systemBytes=${bytes.get(2)}"

    fun hasSpeechPcm(): Boolean = packets.get(1) > 0L || packets.get(2) > 0L
}
