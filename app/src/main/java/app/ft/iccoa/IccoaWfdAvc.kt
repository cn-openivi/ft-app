package app.ft.iccoa

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.util.Locale

/**
 * Bounded Wi-Fi Display AVC negotiation for known CEA 720p30 / 1080p30 modes.
 * A sink's panel display geometry alone is NOT a decoder capability. Unsupported
 * formats keep the existing conservative 1080p/Level4 transport fallback.
 *
 * WFD fields: native, preferred, codec tuples [profile, level, CEA, VESA, HH,
 * latency, min-slice, slice-encoding, frame-rate-control, max-hres, max-vres].
 * Level bitmap: 0=3.1, 1=3.2, 2=4.0, 3=4.1, 4=4.2, 5=5.0, 6=5.1.
 */
internal object IccoaWfdAvc {
    data class Choice(val width: Int, val height: Int, val fps: Int, val profile: Int,
                      val level: Int, val ceaMode: Int) {
        init {
            require(width in 320..1920 && height in 240..1080 && fps in 1..30)
            require(profile == 1 && level in 1..127 && ceaMode in 0..31)
        }
    }

    fun choose(m3: String, requestedFps: Int): Choice? {
        val payload = Regex("(?im)^\\s*wfd_video_formats:\\s*([^\\r\\n]+)")
            .find(m3)?.groupValues?.get(1)?.trim() ?: return null
        val header = payload.split(Regex("\\s+"), limit = 3)
        if (header.size != 3 || !hex(header[0], 2) || !hex(header[1], 2)) return null
        val choices = mutableListOf<Choice>()
        for (codec in header[2].split(Regex("\\s*,\\s*"))) {
            val parts = codec.trim().split(Regex("\\s+"))
            if (parts.size != 11 ||
                !hex(parts[0], 2) || !hex(parts[1], 2) ||
                !hex(parts[2], 8) || !hex(parts[3], 8) || !hex(parts[4], 8) ||
                !hex(parts[5], 2) || !hex(parts[6], 4) || !hex(parts[7], 4) ||
                !hex(parts[8], 2) ||
                !(parts[9] == "none" || hex(parts[9], 4)) ||
                !(parts[10] == "none" || hex(parts[10], 4))) continue
            val profiles = parts[0].toInt(16)
            val levels = parts[1].toInt(16)
            val cea = parts[2].toLong(16)
            if (profiles and 1 == 0) continue // Constrained Baseline first; high-only needs live proof.
            fun addMode(index: Int, w: Int, h: Int, requiredLevelBit: Int) {
                if (cea and (1L shl index) == 0L) return
                val levelBit = (requiredLevelBit..6).firstOrNull { levels and (1 shl it) != 0 }
                    ?: return
                val ceilingW = parts[9].takeIf { it != "none" }?.toInt(16) ?: w
                val ceilingH = parts[10].takeIf { it != "none" }?.toInt(16) ?: h
                if (w > ceilingW || h > ceilingH) return
                choices += Choice(w, h, requestedFps.coerceIn(1, 30), 1, 1 shl levelBit, index)
            }
            addMode(7, 1920, 1080, 2) // Level 4.0, CEA 1080p30
            addMode(5, 1280, 720, 0)  // Level 3.1, CEA 720p30
        }
        return choices.sortedWith(compareByDescending<Choice> { it.width * it.height }
            .thenBy { it.level }).firstOrNull()
    }

    fun m4(choice: Choice): String = String.format(Locale.ROOT,
        "wfd_video_formats: 00 00 %02x %02x %08x 00000000 00000000 00 0000 0000 00 none none",
        choice.profile, choice.level, 1 shl choice.ceaMode)

    private fun hex(value: String, length: Int): Boolean =
        value.length == length && value.all { it in '0'..'9' ||
            it in 'a'..'f' || it in 'A'..'F' }
}

/** Source encoder admission only; never infer a HU decoder from local MediaCodec. */
internal object IccoaWfdSourceEncoder {
    fun supports(choice: IccoaWfdAvc.Choice): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            if (!codec.isEncoder ||
                codec.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            ) return@any false
            val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                caps.profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                } &&
                caps.videoCapabilities.areSizeAndRateSupported(
                    choice.width, choice.height, choice.fps.toDouble())
        }
    }.getOrDefault(false)
}
