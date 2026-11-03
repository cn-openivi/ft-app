package app.ft.carlife

import app.ft.core.MotionVector

/**
 * Original Baidu CarlifeGyroscopeProto / CarlifeAccelerationProto (proto2).
 * The car sends these as CMD_CAR_GYROSCOPE 0x00018011 and
 * CMD_CAR_ACCELERATION 0x00018012, not ICCOA SensorIndex wrappers.
 *
 * Gyro: required int32 type=1, required double X/Y/Z=2/3/4,
 * optional uint64 timestamp=5.
 * Acceleration: required double X/Y/Z=1/2/3, optional timestamp=4.
 * Physical units and sensor-axis mounting remain unconfirmed.
 */
internal object CarLifeInertialWire {
    const val MAX_BYTES = 512

    fun gyro(bytes: ByteArray): MotionVector? = parse(bytes, gyro = true)
    fun acceleration(bytes: ByteArray): MotionVector? = parse(bytes, gyro = false)

    private fun parse(bytes: ByteArray, gyro: Boolean): MotionVector? = runCatching {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val cursor = Cursor(bytes)
        var type: Int? = null
        var timestamp: Long? = null
        val axes = DoubleArray(3)
        val seen = BooleanArray(3)
        while (cursor.more()) {
            val tag = cursor.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            require(field > 0)
            val idx = field - if (gyro) 2 else 1
            when {
                gyro && field == 1 -> {
                    require(wire == 0 && type == null)
                    val n = cursor.varint()
                    require(n in 0L..Int.MAX_VALUE.toLong())
                    type = n.toInt()
                }
                idx in 0..2 -> {
                    require(wire == 1 && !seen[idx])
                    seen[idx] = true
                    axes[idx] = cursor.double()
                    require(axes[idx].isFinite())
                }
                field == if (gyro) 5 else 4 -> {
                    require(wire == 0 && timestamp == null)
                    timestamp = cursor.varint()
                    require(timestamp!! >= 0L)
                }
                else -> cursor.skip(wire)
            }
        }
        // Proto2 explicitly requires all axis members, including a zero value.
        require(seen.all { it } && (!gyro || type != null))
        MotionVector(axes[0], axes[1], axes[2], timestamp, type)
    }.getOrNull()

    private class Cursor(private val data: ByteArray) {
        private var pos = 0
        fun more() = pos < data.size
        fun varint(): Long {
            var r = 0L
            repeat(10) { i ->
                require(pos < data.size)
                val b = data[pos++].toInt() and 0xff
                if (i == 9) require(b <= 1)
                r = r or ((b and 127).toLong() shl (7 * i))
                if (b and 128 == 0) return r
            }
            error("Protobuf varint overflow")
        }
        fun double(): Double {
            require(data.size - pos >= 8)
            var bits = 0L
            repeat(8) { i ->
                bits = bits or ((data[pos++].toLong() and 255L) shl (8 * i))
            }
            return Double.fromBits(bits)
        }
        fun skip(wire: Int) {
            when(wire) {
                0 -> varint()
                1 -> { require(data.size - pos >= 8); pos += 8 }
                2 -> {
                    val n = varint()
                    require(n in 0L..(data.size - pos).toLong())
                    pos += n.toInt()
                }
                5 -> { require(data.size - pos >= 4); pos += 4 }
                else -> error("Unsupported protobuf wire type")
            }
        }
    }
}
