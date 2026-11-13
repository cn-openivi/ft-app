package app.ft.iccoa

/**
 * Read-only parser for AVC H.264 access units received from Android Auto.
 * It never changes the source bitstream. Unknown or malformed layouts fail closed
 * to the existing FT composition pipeline.
 */
internal object IccoaDirectAvc {
    data class Sps(val width: Int, val height: Int, val profileIdc: Int, val levelIdc: Int)

    /** Ensure the ICCOA TS muxer receives Annex B SPS/PPS even if AA used avcC. */
    fun normalizedConfiguration(csd: ByteArray): ByteArray? {
        val nal = units(csd)
        if (nal.none { it.isNotEmpty() && (it[0].toInt() and 31) == 7 } ||
            nal.none { it.isNotEmpty() && (it[0].toInt() and 31) == 8 }) return null
        val result = java.io.ByteArrayOutputStream()
        for (entry in nal) {
            result.write(byteArrayOf(0, 0, 0, 1))
            result.write(entry)
        }
        return result.toByteArray()
    }

    /** Compatibility is scoped to the AVC raster already announced for THIS WFD PLAY. */
    fun compatible(
        sps: Sps?, streamWidth: Int, streamHeight: Int, fps: Int,
        aaWidth: Int, aaHeight: Int
    ): Boolean = sps != null &&
        sps.width == streamWidth && sps.height == streamHeight &&
        sps.profileIdc in setOf(66, 77, 100) && sps.levelIdc in 10..42 &&
        fps in 1..30 && streamWidth in 320..1920 && streamHeight in 240..1080 &&
        app.ft.aa.AaProtocol.standardSize(aaWidth, aaHeight) == (streamWidth to streamHeight)

    /** Inspect only NAL headers: copying full IDR frames here would defeat passthrough. */
    fun isIdr(accessUnit: ByteArray): Boolean {
        val b = accessUnit
        if (b.isEmpty() || b.size > 4 * 1024 * 1024) return false
        fun prefix(at: Int): Int = when {
            at + 4 <= b.size && b[at] == 0.toByte() && b[at + 1] == 0.toByte() &&
                b[at + 2] == 0.toByte() && b[at + 3] == 1.toByte() -> 4
            at + 3 <= b.size && b[at] == 0.toByte() && b[at + 1] == 0.toByte() &&
                b[at + 2] == 1.toByte() -> 3
            else -> 0
        }
        val first = prefix(0)
        if (first > 0) {
            var at = 0
            while (at < b.size) {
                val n = prefix(at)
                if (n > 0) {
                    val nal = at + n
                    if (nal < b.size && (b[nal].toInt() and 31) == 5) return true
                    at = nal
                } else at++
            }
            return false
        }
        if (b.size >= 5) {
            var at = 0
            var sawLengthPrefix = false
            while (at + 4 <= b.size) {
                val n = ((b[at].toInt() and 255) shl 24) or
                    ((b[at + 1].toInt() and 255) shl 16) or
                    ((b[at + 2].toInt() and 255) shl 8) or (b[at + 3].toInt() and 255)
                if (n <= 0 || n > b.size - at - 4) break
                sawLengthPrefix = true
                if ((b[at + 4].toInt() and 31) == 5) return true
                at += 4 + n
            }
            if (sawLengthPrefix && at == b.size) return false
        }
        return (b[0].toInt() and 31) == 5
    }

    fun sps(configuration: ByteArray): Sps? = runCatching {
        val nal = units(configuration).firstOrNull { it.isNotEmpty() && it[0].toInt() and 31 == 7 }
            ?: return@runCatching null
        require(nal.size in 5..4096)
        val rbsp = ArrayList<Byte>(nal.size)
        var zeroes = 0
        for (i in 1 until nal.size) {
            val value = nal[i].toInt() and 255
            if (zeroes >= 2 && value == 3) { zeroes = 0; continue }
            rbsp.add(nal[i])
            zeroes = if (value == 0) zeroes + 1 else 0
        }
        val bits = Bits(rbsp.toByteArray())
        val profile = bits.read(8)
        bits.read(8) // constraint flags and reserved bits
        val level = bits.read(8)
        bits.ue() // seq_parameter_set_id
        var chroma = 1
        var separateColourPlane = false
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = bits.ue()
            require(chroma in 0..3)
            if (chroma == 3) separateColourPlane = bits.read(1) != 0
            bits.ue() // bit_depth_luma_minus8
            bits.ue() // bit_depth_chroma_minus8
            bits.read(1) // qpprime_y_zero_transform_bypass_flag
            if (bits.read(1) != 0) {
                repeat(if (chroma == 3) 12 else 8) { i ->
                    if (bits.read(1) != 0) {
                        val size = if (i < 6) 16 else 64
                        var last = 8; var next = 8
                        repeat(size) {
                            if (next != 0) next = (last + bits.se() + 256) % 256
                            last = if (next == 0) last else next
                        }
                    }
                }
            }
        }
        bits.ue() // log2_max_frame_num_minus4
        when (bits.ue()) { // pic_order_cnt_type
            0 -> bits.ue()
            1 -> {
                bits.read(1); bits.se(); bits.se()
                val count = bits.ue()
                require(count <= 256)
                repeat(count) { bits.se() }
            }
            2 -> Unit
            else -> error("Unsupported POC type")
        }
        bits.ue() // max_num_ref_frames
        bits.read(1) // gaps_in_frame_num_value_allowed_flag
        val mbsWide = bits.ue() + 1
        val mapUnitsHigh = bits.ue() + 1
        require(mbsWide in 1..256 && mapUnitsHigh in 1..256)
        val frameOnly = bits.read(1) != 0
        if (!frameOnly) bits.read(1) // mb_adaptive_frame_field_flag
        bits.read(1) // direct_8x8_inference_flag
        val cropFlag = bits.read(1) != 0
        val cropLeft = if (cropFlag) bits.ue() else 0
        val cropRight = if (cropFlag) bits.ue() else 0
        val cropTop = if (cropFlag) bits.ue() else 0
        val cropBottom = if (cropFlag) bits.ue() else 0
        val chromaArray = if (separateColourPlane) 0 else chroma
        val cropUnitX = if (chromaArray in 1..2) 2 else 1
        val cropUnitY = (if (chromaArray == 1) 2 else 1) * (if (frameOnly) 1 else 2)
        val width = mbsWide * 16 - (cropLeft + cropRight) * cropUnitX
        val height = mapUnitsHigh * 16 * (if (frameOnly) 1 else 2) -
            (cropTop + cropBottom) * cropUnitY
        require(frameOnly && width in 320..4096 && height in 240..2160)
        Sps(width, height, profile, level)
    }.getOrNull()

    /**
     * Extract raw NALs from Annex B, AVCC length-prefix or a lone NAL.
     * The ICCOA muxer performs the actual Annex B repackaging.
     */
    private fun units(data: ByteArray): List<ByteArray> {
        if (data.isEmpty() || data.size > 4 * 1024 * 1024) return emptyList()
        fun code(at: Int): Int = when {
            at + 4 <= data.size && data[at] == 0.toByte() && data[at + 1] == 0.toByte() &&
                data[at + 2] == 0.toByte() && data[at + 3] == 1.toByte() -> 4
            at + 3 <= data.size && data[at] == 0.toByte() && data[at + 1] == 0.toByte() &&
                data[at + 2] == 1.toByte() -> 3
            else -> 0
        }
        if (code(0) > 0) {
            val result = ArrayList<ByteArray>()
            var at = 0
            while (at < data.size) {
                val prefix = code(at)
                if (prefix == 0) break
                val from = at + prefix
                var to = from
                while (to < data.size && code(to) == 0) to++
                if (to > from) result.add(data.copyOfRange(from, to))
                at = to
            }
            return result
        }
        // ISO/IEC 14496-15 AVCDecoderConfigurationRecord (avcC).
        if (data.size >= 8 && (data[0].toInt() and 255) == 1) {
            val result = ArrayList<ByteArray>()
            var at = 5
            val count = data[at++].toInt() and 31
            if (count == 0) return emptyList()
            fun takeNal(): Boolean {
                if (at + 2 > data.size) return false
                val size = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
                at += 2
                if (size <= 0 || size > data.size - at) return false
                result.add(data.copyOfRange(at, at + size))
                at += size
                return true
            }
            repeat(count) { if (!takeNal()) return emptyList() }
            if (at >= data.size) return emptyList()
            val ppsCount = data[at++].toInt() and 255
            repeat(ppsCount) { if (!takeNal()) return emptyList() }
            if (at == data.size) return result
        }
        if (data.size >= 5) {
            val result = ArrayList<ByteArray>()
            var at = 0
            while (at + 4 <= data.size) {
                val n = ((data[at].toInt() and 255) shl 24) or
                    ((data[at + 1].toInt() and 255) shl 16) or
                    ((data[at + 2].toInt() and 255) shl 8) or (data[at + 3].toInt() and 255)
                if (n <= 0 || n > data.size - at - 4) break
                result.add(data.copyOfRange(at + 4, at + 4 + n))
                at += 4 + n
            }
            if (at == data.size && result.isNotEmpty()) return result
        }
        return listOf(data)
    }

    private class Bits(private val bytes: ByteArray) {
        private var position = 0
        fun read(count: Int): Int {
            require(count in 0..30 && position + count <= bytes.size * 8)
            var value = 0
            repeat(count) {
                val b = bytes[position / 8].toInt() and 255
                value = (value shl 1) or ((b shr (7 - position % 8)) and 1)
                position++
            }
            return value
        }
        fun ue(): Int {
            var zeroes = 0
            while (read(1) == 0) { zeroes++; require(zeroes <= 24) }
            return ((1 shl zeroes) - 1) + read(zeroes)
        }
        fun se(): Int {
            val n = ue()
            return if (n and 1 == 0) -(n / 2) else (n + 1) / 2
        }
    }
}

/**
 * Align plausible AA microsecond frame deltas with ICCOA's monotonic audio clock.
 * Unknown AA timestamp units, resets, discontinuities and implausible deltas use
 * local presentation time; opaque absolute AA timestamps are never forwarded.
 */
internal class IccoaDirectClock(private val nowUs: () -> Long = { System.nanoTime() / 1000 }) {
    private var previous = Long.MIN_VALUE
    private var previousInput = Long.MIN_VALUE

    fun next(): Long = nextWithInput(null)

    fun next(inputPtsUs: Long): Long = nextWithInput(inputPtsUs)

    private fun nextWithInput(input: Long?): Long {
        val now = nowUs()
        val delta = if (input != null && previousInput != Long.MIN_VALUE &&
            input > previousInput) input - previousInput else -1L
        val candidate = if (previous != Long.MIN_VALUE && delta in 10_000L..200_000L)
            previous + delta else now
        // Bound jitter correction to keep output video near host-clocked AAC.
        val aligned = candidate.coerceIn(now - 100_000L, now + 100_000L)
        val result = if (previous == Long.MIN_VALUE) now else maxOf(aligned, previous + 1L)
        previous = result
        previousInput = input ?: Long.MIN_VALUE
        return result
    }

    fun reset() {
        previous = Long.MIN_VALUE
        previousInput = Long.MIN_VALUE
    }
}
