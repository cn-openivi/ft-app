package app.ft.iccoa

/**
 * Read-only parser for AVC H.264 access units received from Android Auto.
 * It never changes the source bitstream. Unknown or malformed layouts fail closed
 * to the existing FT composition pipeline.
 */
internal object IccoaDirectAvc {
    data class Sps(val width: Int, val height: Int, val profileIdc: Int, val levelIdc: Int)

    fun isIdr(accessUnit: ByteArray): Boolean =
        units(accessUnit).any { it.isNotEmpty() && it[0].toInt() and 31 == 5 }

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
        if (data.size >= 8) {
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

/** A/V share the Android host's monotonic time domain; never pass opaque AA timestamps
 * unchanged into ICCOA's System.nanoTime-based audio queue until their units are proven.
 */
internal class IccoaDirectClock(private val nowUs: () -> Long = { System.nanoTime() / 1000 }) {
    private var previous = Long.MIN_VALUE
    fun next(): Long {
        val sample = nowUs()
        val value = if (previous == Long.MIN_VALUE) sample else maxOf(sample, previous + 1)
        previous = value
        return value
    }
    fun reset() { previous = Long.MIN_VALUE }
}
