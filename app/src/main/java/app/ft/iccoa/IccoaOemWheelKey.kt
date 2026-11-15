package app.ft.iccoa

/**
 * Exact OEM UCar SDK path in the owner's FAW RAV4 Carlink.apk:
 * CarMode HardKeyListener -> CarLinkMessageHandler event 30 ->
 * CarLinkAdapter.handleControlCarLinkMedia -> UCarAdapter.sendKeyEvent ->
 * UCarProto.CustomKeyEvent, CAR CONTROL method 12.
 *
 * Original APK DEX classes2.dex 0x2c0f50 / 0x2b728c and
 * classes3.dex 0x3c1274 / 0x3cb37c. Protobuf:
 *   action=1 (proto3 default 0 = DOWN; 1 = UP; 2 = PRESS),
 *   keycode=2, meta_state=3, timestamp=4.
 * OEM KeyCodeType.MEDIA_NEXT=87 and PREVIOUS=88. The enum declared
 * inside ucar.proto has an older subset, so decode raw int32 values.
 *
 * This parser is read-only, small and strict. Never interpret arbitrary
 * UIBC keyboard events, write to CAN or invent a HU key code.
 */
internal object IccoaOemWheelKey {
    const val CATEGORY = 1
    const val CONTROL_METHOD = 12
    const val NEXT = 87
    const val PREVIOUS = 88

    internal data class Event(
        val code: Int,
        val action: Int,
        val metaState: Int,
        val sourceTimestampMs: Long?
    ) {
        val isTrack: Boolean get() = code == NEXT || code == PREVIOUS
        /** CarLink synthesizes DOWN + UP on a regular physical key release. */
        val dispatchTrack: Boolean get() = isTrack && (action == 0 || action == 2)
    }

    /** Only accept a bounded protobuf with a single, explicit keycode field. */
    fun decode(payload: ByteArray): Event? = runCatching {
        if (payload.isEmpty() || payload.size > 128) return@runCatching null
        var position = 0
        fun readVarint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(position < payload.size)
                val b = payload[position++].toInt() and 0xff
                if (shift == 63) require(b <= 1)
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
            }
            error("Varint overflow")
        }
        var action = 0
        var keyCode: Int? = null
        var meta = 0
        var timestamp: Long? = null
        val seen = HashSet<Int>()
        while (position < payload.size) {
            val tag = readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            require(field in 1..64 && seen.add(field))
            // Unknown proto3 fields are legal but never key instructions.
            if (field in 1..4) require(wire == 0)
            val value = when (wire) {
                0 -> readVarint()
                1 -> { require(payload.size - position >= 8); position += 8; 0L }
                2 -> {
                    val count = readVarint()
                    require(count <= (payload.size - position).toLong())
                    position += count.toInt()
                    0L
                }
                5 -> { require(payload.size - position >= 4); position += 4; 0L }
                else -> error("Unsupported protobuf wire type")
            }
            when (field) {
                1 -> { require(value in 0L..2L); action = value.toInt() }
                2 -> { require(value in 0L..Int.MAX_VALUE.toLong()); keyCode = value.toInt() }
                3 -> { require(value in 0L..Int.MAX_VALUE.toLong()); meta = value.toInt() }
                4 -> timestamp = value.takeIf { it > 0L }
            }
        }
        val code = keyCode ?: return@runCatching null
        if (code != NEXT && code != PREVIOUS) return@runCatching null
        Event(code, action, meta, timestamp)
    }.getOrNull()
}
