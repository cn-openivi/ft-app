package app.ft.carplay

/**
 * Original, dependency-free iAP2 LINK packet framing (not the Apple CarPlay application protocol).
 *
 * Publicly documented wire format: FF 5A, 16-bit total packet length, control,
 * sequence, acknowledgement, session, 8-bit additive header checksum; a nonempty
 * payload has its own one-byte additive checksum. The checksum of each protected
 * region INCLUDING its checksum byte is zero modulo 256.
 *
 * This is a codec only. It neither opens RFCOMM nor claims MFi auth, iAP2 session
 * negotiation, CarPlayAvailability, Wi-Fi transfer, AirPlay, video, or calls.
 * No copied third-party implementation or private keys.
 */
internal object Iap2LinkFrameCodec {
    const val HEADER_BYTES = 9
    const val MAX_FRAME_BYTES = 0xffff

    data class Frame(
        val control: Int,
        val sequence: Int,
        val acknowledgement: Int,
        val session: Int,
        val payload: ByteArray
    )

    fun encode(
        control: Int,
        sequence: Int,
        acknowledgement: Int,
        session: Int,
        payload: ByteArray = byteArrayOf()
    ): ByteArray {
        require(listOf(control, sequence, acknowledgement, session).all { it in 0..255 })
        val total = HEADER_BYTES + payload.size + if (payload.isNotEmpty()) 1 else 0
        require(total <= MAX_FRAME_BYTES) { "iAP2 frame exceeds 16-bit wire length" }
        val wire = ByteArray(total)
        wire[0] = 0xff.toByte()
        wire[1] = 0x5a.toByte()
        wire[2] = (total ushr 8).toByte()
        wire[3] = total.toByte()
        wire[4] = control.toByte()
        wire[5] = sequence.toByte()
        wire[6] = acknowledgement.toByte()
        wire[7] = session.toByte()
        wire[8] = checksum(wire, 0, 8).toByte()
        if (payload.isNotEmpty()) {
            payload.copyInto(wire, destinationOffset = HEADER_BYTES)
            wire[wire.lastIndex] = checksum(wire, HEADER_BYTES, payload.size).toByte()
        }
        return wire
    }

    fun decode(wire: ByteArray): Frame? {
        if (wire.size < HEADER_BYTES || wire[0].toInt() and 0xff != 0xff ||
            wire[1].toInt() and 0xff != 0x5a) return null
        val length = ((wire[2].toInt() and 0xff) shl 8) or (wire[3].toInt() and 0xff)
        if (length != wire.size || length !in HEADER_BYTES..MAX_FRAME_BYTES) return null
        if (checksum(wire, 0, HEADER_BYTES) != 0) return null
        if (length > HEADER_BYTES && (
            length < HEADER_BYTES + 2 ||
            checksum(wire, HEADER_BYTES, length - HEADER_BYTES) != 0
        )) return null
        return Frame(
            control = wire[4].toInt() and 0xff,
            sequence = wire[5].toInt() and 0xff,
            acknowledgement = wire[6].toInt() and 0xff,
            session = wire[7].toInt() and 0xff,
            payload = if (length == HEADER_BYTES) byteArrayOf()
                else wire.copyOfRange(HEADER_BYTES, length - 1)
        )
    }

    /** Number of bytes already present at offset, not the entire input buffer. */
    fun declaredLength(input: ByteArray, offset: Int): Int? {
        if (offset < 0 || input.size - offset < 4) return null
        if (input[offset].toInt() and 0xff != 0xff ||
            input[offset + 1].toInt() and 0xff != 0x5a) return null
        val n = ((input[offset + 2].toInt() and 0xff) shl 8) or
            (input[offset + 3].toInt() and 0xff)
        return n.takeIf { it in HEADER_BYTES..MAX_FRAME_BYTES }
    }

    private fun checksum(bytes: ByteArray, from: Int, count: Int): Int {
        var acc = 0
        for (i in from until from + count) acc = (acc + (bytes[i].toInt() and 0xff)) and 0xff
        return (-acc) and 0xff
    }
}

/** Reassembles split iAP2 LINK frames; malformed chunks resync without unlimited buffering. */
internal class Iap2LinkStreamParser {
    private var pending = ByteArray(0)

    fun reset() { pending = byteArrayOf() }

    fun accept(chunk: ByteArray): List<Iap2LinkFrameCodec.Frame> {
        require(chunk.size <= Iap2LinkFrameCodec.MAX_FRAME_BYTES) {
            "single input exceeds bounded iAP2 reader size"
        }
        // Bounding the queue prevents a malicious or unresponsive peer from exhausting RAM.
        pending += chunk
        val out = mutableListOf<Iap2LinkFrameCodec.Frame>()
        while (pending.size >= 2) {
            if (pending[0].toInt() and 0xff != 0xff ||
                pending[1].toInt() and 0xff != 0x5a) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < 4) break
            val declared = Iap2LinkFrameCodec.declaredLength(pending, 0)
            if (declared == null) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < Iap2LinkFrameCodec.HEADER_BYTES) break
            // Do not pin 64KiB waiting for a corrupted length header.
            if (pending.copyOfRange(0, Iap2LinkFrameCodec.HEADER_BYTES).let {
                Iap2LinkFrameCodec.declaredLength(it, 0) == null ||
                    it.fold(0) { a, b -> (a + (b.toInt() and 0xff)) and 0xff } != 0
            }) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < declared) break
            val frame = Iap2LinkFrameCodec.decode(pending.copyOfRange(0, declared))
            if (frame == null) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            out.add(frame)
            pending = pending.copyOfRange(declared, pending.size)
        }
        return out
    }
}
