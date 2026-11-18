package app.ft.aa

import app.ft.core.AudioIngressTiming

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
    private val arrival = Array(3) { AudioIngressTiming() }

    fun navigationMessage() {
        navigationMessages.incrementAndGet()
    }

    fun pcm(channel: Int, byteCount: Int): String? {
        if (byteCount <= 0) return null
        val i = when (channel) {
            AaProtocol.CH_MEDIA_AUDIO -> 0
            AaProtocol.CH_SPEECH_AUDIO -> 1
            AaProtocol.CH_SYSTEM_AUDIO -> 2
            else -> return null
        }
        packets.incrementAndGet(i)
        bytes.addAndGet(i, byteCount.toLong())
        val timing = arrival[i].onPcm(System.nanoTime(), byteCount,
            if (i == 0) 48000 else 16000, if (i == 0) 2 else 1)
        return timing?.let { "AA_AUDIO_TIMING lane=${if (i == 0) "media" else if (i == 1) "speech" else "system"} $it" }
    }

    fun streamBoundary(channel: Int) {
        val i = when (channel) {
            AaProtocol.CH_MEDIA_AUDIO -> 0
            AaProtocol.CH_SPEECH_AUDIO -> 1
            AaProtocol.CH_SYSTEM_AUDIO -> 2
            else -> return
        }
        arrival[i].streamBoundary()
    }

    // Read only on session shutdown, never per PCM frame.
    fun summary(): String =
        "navigationMetadata=${navigationMessages.get()} " +
            "mediaPackets=${packets.get(0)} mediaBytes=${bytes.get(0)} " +
            "speechPackets=${packets.get(1)} speechBytes=${bytes.get(1)} " +
            "systemPackets=${packets.get(2)} systemBytes=${bytes.get(2)} " +
            "mediaTiming=[${arrival[0].summary()}] " +
            "speechTiming=[${arrival[1].summary()}] " +
            "systemTiming=[${arrival[2].summary()}]"

    fun hasSpeechPcm(): Boolean = packets.get(1) > 0L || packets.get(2) > 0L
}
