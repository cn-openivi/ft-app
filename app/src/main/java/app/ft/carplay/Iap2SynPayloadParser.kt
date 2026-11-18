package app.ft.carplay

/**
 * Bounded decoder for the real accessory's iAP2 LINK SYN payload.
 *
 * Version-1 session entries follow the 10-byte fixed header directly.
 * Version >=2 uses a bounded extension section and an explicit session count.
 * This distinction is required before trusting any CONTROL session ID/version.
 *
 * The wire format comes from public independent iAP2 research, not a verified
 * FAW Toyota capture. No outgoing SYN, MFi credential or vehicle command here.
 */
internal object Iap2SynPayloadParser {
    data class Session(val id: Int, val type: Int, val version: Int)

    data class Syn(
        val linkVersion: Int,
        val maxOutstanding: Int,
        val maxPacketBytes: Int,
        val maxRetransmissions: Int,
        val sessions: List<Session>
    )

    fun parse(payload: ByteArray): Syn? {
        if (payload.size < 13) return null
        val version = unsigned(payload[0])
        if (version !in 1..3) return null
        val outstanding = unsigned(payload[1])
        val maxPacket = u16(payload, 2)
        val ackTimeout = u16(payload, 4)
        val cumAckTimeout = u16(payload, 6)
        val maxRetransmissions = unsigned(payload[8])
        val maxCumAck = unsigned(payload[9])
        // No-retransmission variants are outside this bounded experimental
        // profile; do not silently turn a required zero timer into a default.
        if (outstanding !in 1..127 || maxPacket <= 23 ||
            ackTimeout <= 19 || cumAckTimeout < 10 ||
            maxRetransmissions !in 1..30 || maxCumAck > outstanding) return null

        val entriesAt: Int
        val sessionCount: Int
        if (version == 1) {
            if ((payload.size - 10) % 3 != 0) return null
            entriesAt = 10
            sessionCount = (payload.size - entriesAt) / 3
        } else {
            if (payload.size < 15) return null
            val extCount = unsigned(payload[10])
            if (extCount > 5) return null
            val countAt = 11 + extCount
            if (countAt >= payload.size) return null
            sessionCount = unsigned(payload[countAt])
            entriesAt = countAt + 1
            if (payload.size - entriesAt != 3 * sessionCount) return null
        }
        if (sessionCount !in 1..5) return null
        val seenIds = HashSet<Int>()
        val sessions = ArrayList<Session>(sessionCount)
        repeat(sessionCount) { index ->
            val off = entriesAt + 3 * index
            val id = unsigned(payload[off])
            val rawType = unsigned(payload[off + 1])
            val type = rawType and 0x7f
            val controlVersion = unsigned(payload[off + 2])
            if (id == 0 || !seenIds.add(id) || type !in 0..4 ||
                (rawType and 0x80) != 0) return null
            sessions.add(Session(id, type, controlVersion))
        }

        return Syn(version, outstanding, maxPacket, maxRetransmissions, sessions)
    }

    private fun unsigned(b: Byte): Int = b.toInt() and 0xff
    private fun u16(bytes: ByteArray, at: Int): Int =
        (unsigned(bytes[at]) shl 8) or unsigned(bytes[at + 1])
}
