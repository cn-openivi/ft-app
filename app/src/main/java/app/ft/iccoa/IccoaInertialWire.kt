package app.ft.iccoa

/**
 * OEM FAW Toyota Carlink.apk's ucar.proto:
 * SensorIndex.gyro_scope = 3 (GyroScope), acceleration = 4 (Acceleration).
 *
 * This parser preserves uncalibrated values. The OEM protobuf defines no
 * coordinate frame or physical units for either vector; never infer them.
 * A SensorIndex wrapper is accepted only if it contains the correct field.
 */
internal object IccoaInertialWire {
    const val GYRO_METHOD = 3
    const val ACCEL_METHOD = 4
    private const val MAX_BYTES = 512

    internal data class Sample(
        val x: Double,
        val y: Double,
        val z: Double,
        val deviceTimestampRaw: Long?,
        val gyroType: Int? = null
    )

    fun decodeGyro(payload: ByteArray): Sample? {
        if (payload.isEmpty() || payload.size > MAX_BYTES) return null
        return parse(payload, gyro = true)
            ?: unwrap(payload, 3)?.let { parse(it, gyro = true) }
    }

    fun decodeAcceleration(payload: ByteArray): Sample? {
        if (payload.isEmpty() || payload.size > MAX_BYTES) return null
        return parse(payload, gyro = false)
            ?: unwrap(payload, 4)?.let { parse(it, gyro = false) }
    }

    private fun unwrap(payload: ByteArray, fieldWanted: Int): ByteArray? = runCatching {
        val p = Reader(payload)
        var selected: ByteArray? = null
        while (p.more()) {
            val tag = p.varint()
            val field = (tag ushr 3).toInt()
            require(field in 1..8 && (tag and 7L) == 2L)
            val bytes = p.lengthDelimited()
            if (field == fieldWanted) {
                require(selected == null)
                selected = bytes
            }
        }
        selected
    }.getOrNull()

    private fun parse(payload: ByteArray, gyro: Boolean): Sample? = runCatching {
        val p = Reader(payload)
        val axes = DoubleArray(3)
        val seen = BooleanArray(3)
        var gyroType: Int? = null
        var timestamp: Long? = null
        while (p.more()) {
            val tag = p.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            require(field in 1..2047)
            val axis = if (gyro) field - 2 else field - 1
            when {
                gyro && field == 1 -> {
                    require(wire == 0 && gyroType == null)
                    val type = p.varint()
                    require(type in 0L..2L)
                    gyroType = type.toInt()
                }
                axis in 0..2 -> {
                    require(wire == 1 && !seen[axis])
                    seen[axis] = true
                    axes[axis] = p.double()
                    require(axes[axis].isFinite())
                }
                field == (if (gyro) 5 else 4) -> {
                    require(wire == 0 && timestamp == null)
                    timestamp = p.varint()
                    require(timestamp!! >= 0L)
                }
                else -> p.skip(wire)
            }
        }
        // Proto3 omits zero-valued axes. A timestamp can attest a zero vector.
        require(seen.any { it } || timestamp != null)
        Sample(axes[0], axes[1], axes[2], timestamp, gyroType)
    }.getOrNull()

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0
        fun more() = pos < bytes.size
        fun varint(): Long {
            var value = 0L
            repeat(10) { i ->
                require(pos < bytes.size)
                val v = bytes[pos++].toInt() and 0xFF
                if (i == 9) require(v <= 1)
                value = value or ((v and 0x7F).toLong() shl (7 * i))
                if (v and 0x80 == 0) return value
            }
            error("Varint overflow")
        }
        fun double(): Double {
            require(bytes.size - pos >= 8)
            var bits = 0L
            repeat(8) { i ->
                bits = bits or ((bytes[pos++].toLong() and 0xFFL) shl (8 * i))
            }
            return Double.fromBits(bits)
        }
        fun lengthDelimited(): ByteArray {
            val len = varint()
            require(len in 0L..(bytes.size - pos).toLong())
            val result = bytes.copyOfRange(pos, pos + len.toInt())
            pos += len.toInt()
            return result
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> { require(bytes.size - pos >= 8); pos += 8 }
                2 -> lengthDelimited()
                5 -> { require(bytes.size - pos >= 4); pos += 4 }
                else -> error("Invalid protobuf wire type")
            }
        }
    }
}
