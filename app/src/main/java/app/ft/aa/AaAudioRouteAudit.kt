package app.ft.aa

/**
 * Privacy-preserving diagnostic of audio arriving FROM Android Auto.
 *
 * NAVIGATION is route/turn metadata, not voice PCM. Audio can arrive only on
 * MEDIA/SPEECH/SYSTEM channel payloads. Packet/byte counts cannot prove that
 * a navigation app uses a particular Android AudioAttributes.USAGE_*.
 */
internal class AaAudioRouteAudit {
    private var navigationMessages = 0L
    private val packets = LongArray(3)
    private val bytes = LongArray(3)

    @Synchronized fun navigationMessage() {
        navigationMessages++
    }

    @Synchronized fun pcm(channel: Int, byteCount: Int) {
        if (byteCount <= 0) return
        val i = when (channel) {
            AaProtocol.CH_MEDIA_AUDIO -> 0
            AaProtocol.CH_SPEECH_AUDIO -> 1
            AaProtocol.CH_SYSTEM_AUDIO -> 2
            else -> return
        }
        packets[i]++
        bytes[i] += byteCount
    }

    @Synchronized fun summary(): String =
        "navigationMetadata=$navigationMessages " +
            "mediaPackets=${packets[0]} mediaBytes=${bytes[0]} " +
            "speechPackets=${packets[1]} speechBytes=${bytes[1]} " +
            "systemPackets=${packets[2]} systemBytes=${bytes[2]}"

    @Synchronized fun hasSpeechPcm(): Boolean = packets[1] > 0L || packets[2] > 0L
}
