package app.ft.carplay

/**
 * Incremental, bounded decoder for iAP2 CONTROL messages inside an already
 * negotiated LINK session. Bluetooth and LINK packets may fragment one 0x4040
 * message or coalesce several messages into a single read.
 *
 * This class does NOT authenticate an accessory, negotiate a session, send
 * CarPlayAvailability or disclose any parameter values to diagnostic logs.
 */
internal class Iap2ControlStreamParser {
    private var pending = byteArrayOf()

    fun reset() {
        pending = byteArrayOf()
    }

    fun accept(chunk: ByteArray): List<Iap2ControlSessionCodec.Message> {
        require(chunk.size <= Iap2ControlSessionCodec.MAX_MESSAGE_BYTES) {
            "one iAP2 CONTROL input exceeds the maximum message size"
        }
        if (chunk.isEmpty()) return emptyList()
        // Any incomplete valid message is at most 65,534 bytes; therefore two
        // bounded adjacent inputs require at most 131,069 bytes before parsing.
        val data = pending + chunk
        val messages = ArrayList<Iap2ControlSessionCodec.Message>()
        var offset = 0
        while (data.size - offset >= 2) {
            if ((data[offset].toInt() and 0xff) != 0x40 ||
                (data[offset + 1].toInt() and 0xff) != 0x40) {
                offset++
                continue
            }
            if (data.size - offset < 4) break
            val declared = ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)
            if (declared !in Iap2ControlSessionCodec.HEADER_BYTES..
                    Iap2ControlSessionCodec.MAX_MESSAGE_BYTES) {
                offset++
                continue
            }
            if (data.size - offset < declared) {
                // A corrupt prefix can mimic a valid (but huge) length, such as
                // 40 40 40 00, and hide a complete following CONTROL frame.
                // Recover only if a LATER, fully framed and TLV-validated
                // message exists in the bytes we actually received. Never
                // manufacture an ACK or accept a partial candidate as complete.
                val recovered = findCompleteLaterMessage(data, offset + 1)
                if (recovered == -1) break
                offset = recovered
                continue
            }
            val decoded = Iap2ControlSessionCodec.decode(
                data.copyOfRange(offset, offset + declared)
            )
            if (decoded == null) {
                // Corrupt envelope/TLV; search for a later real message rather
                // than passing opaque bytes through as a success.
                offset++
                continue
            }
            messages.add(decoded)
            offset += declared
        }
        pending = data.copyOfRange(offset, data.size)
        return messages
    }

    /** Resync only to an independently complete and valid CONTROL envelope. */
    private fun findCompleteLaterMessage(data: ByteArray, after: Int): Int {
        val max = data.size - Iap2ControlSessionCodec.HEADER_BYTES
        for (pos in after..max) {
            if (data[pos].toInt() != 0x40 || data[pos + 1].toInt() != 0x40)
                continue
            val n = ((data[pos + 2].toInt() and 0xff) shl 8) or
                (data[pos + 3].toInt() and 0xff)
            if (n < Iap2ControlSessionCodec.HEADER_BYTES || n > data.size - pos)
                continue
            if (Iap2ControlSessionCodec.decode(data.copyOfRange(pos, pos + n)) != null)
                return pos
        }
        return -1
    }
}
