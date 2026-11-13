package app.ft.iccoa

import app.ft.core.ProtoWriter

/**
 * RAV4 OEM Carlink.apk ucar.proto ControlIndex methods 2/3.
 * Requests carry ServerType enum: SENSOR=1, AUDIO=2, UIBC=3.
 * Only the SENSOR port is mapped: FT binds this authenticated lane explicitly.
 */
internal object IccoaPortNegotiation {
    const val REQUEST = 2
    const val RESPONSE = 3
    const val SENSOR = 1
    const val SENSOR_PORT = 57239

    fun responseFor(request: ByteArray): ByteArray? {
        // Decode complete protobuf wire fields, not one byte-for-byte serialization.
        // Forward-compatible unknown fields are safe; duplicate SENSOR,
        // malformed/truncated payloads and unsupported enum values are not.
        if (request.isEmpty() || request.size > 512) return null
        var offset = 0
        var hasSensor = false
        fun varint(): Long? {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (offset == request.size) return null
                val byte = request[offset++].toInt() and 255
                if (shift == 63 && byte > 1) return null
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            return null
        }
        while (offset < request.size) {
            val tag = varint() ?: return null
            val field = tag ushr 3
            val wire = (tag and 7L).toInt()
            if (field == 0L || field > 536870911L) return null
            if (field == 1L) {
                if (hasSensor || wire != 0 || varint() != SENSOR.toLong()) return null
                hasSensor = true
                continue
            }
            when (wire) {
                0 -> if (varint() == null) return null
                1 -> if (request.size - offset < 8) return null else offset += 8
                2 -> {
                    val length = varint() ?: return null
                    if (length < 0 || length > request.size - offset) return null
                    offset += length.toInt()
                }
                5 -> if (request.size - offset < 4) return null else offset += 4
                else -> return null
            }
        }
        if (!hasSensor) return null
        return ProtoWriter().enum(1, 1).int32(2, SENSOR_PORT).toByteArray()
    }
}
