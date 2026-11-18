package app.ft.carplay

/**
 * Clean-room iAP2 CONTROL-session message framing (not an iAP2/MFi authentication engine).
 *
 * Message wire format: 0x40 0x40 | 16-bit total length | 16-bit message ID |
 * zero or more TLV parameters (16-bit length including the 4-byte TLV header, 16-bit ID).
 * The CONTROL message is the PAYLOAD of an already-established iAP2 link session.
 *
 * FT200 phone-side direction: the phone offers CarPlayAvailability (0x4300);
 * a compatible HU may answer with CarPlayStartSession (0x4301).
 * Receiving these bytes is not proof of authentication, Wi-Fi handover or ACTIVE.
 *
 * Does not embed certificates, keys, synthetic identifiers, SSIDs or passphrases.
 * Parameter values should NEVER be printed to DiagLog, as they can contain credentials.
 */
internal object Iap2ControlSessionCodec {
    const val CARPLAY_AVAILABILITY = 0x4300
    const val CARPLAY_START_SESSION = 0x4301
    const val HEADER_BYTES = 6
    const val TLV_HEADER_BYTES = 4
    const val MAX_MESSAGE_BYTES = 65535

    data class Parameter(val id: Int, val value: ByteArray)
    data class Message(val id: Int, val parameters: List<Parameter>)

    enum class PhoneSideRole {
        OUTBOUND_AVAILABILITY, INBOUND_START_SESSION, OTHER
    }

    /** Do not log parameter values. IDs and lengths are sufficient for redacted diagnostics. */
    data class RedactedSummary(
        val messageId: Int,
        val role: PhoneSideRole,
        val parameterIdsAndSizes: List<Pair<Int, Int>>
    )

    fun classify(messageId: Int): PhoneSideRole = when (messageId) {
        CARPLAY_AVAILABILITY -> PhoneSideRole.OUTBOUND_AVAILABILITY
        CARPLAY_START_SESSION -> PhoneSideRole.INBOUND_START_SESSION
        else -> PhoneSideRole.OTHER
    }

    fun encode(message: Message): ByteArray {
        require(message.id in 0..65535)
        var size = HEADER_BYTES
        for (p in message.parameters) {
            require(p.id in 0..65535)
            require(p.value.size <= MAX_MESSAGE_BYTES - TLV_HEADER_BYTES)
            size += TLV_HEADER_BYTES + p.value.size
            require(size <= MAX_MESSAGE_BYTES) { "iAP2 CONTROL message too large" }
        }
        val output = ByteArray(size)
        output[0] = 0x40
        output[1] = 0x40
        put16(output, 2, size)
        put16(output, 4, message.id)
        var offset = HEADER_BYTES
        for (p in message.parameters) {
            val tlvSize = TLV_HEADER_BYTES + p.value.size
            put16(output, offset, tlvSize)
            put16(output, offset + 2, p.id)
            p.value.copyInto(output, offset + TLV_HEADER_BYTES)
            offset += tlvSize
        }
        return output
    }

    /**
     * Strict framing: rejects zero/short TLV, truncated value, malformed length,
     * trailing bytes and nested TLVs with invalid envelope where the caller parses them.
     */
    fun decode(bytes: ByteArray): Message? {
        if (bytes.size !in HEADER_BYTES..MAX_MESSAGE_BYTES ||
            (bytes[0].toInt() and 255) != 0x40 ||
            (bytes[1].toInt() and 255) != 0x40) return null
        if (u16(bytes, 2) != bytes.size) return null
        val id = u16(bytes, 4)
        val parameters = ArrayList<Parameter>()
        var offset = HEADER_BYTES
        while (offset < bytes.size) {
            if (bytes.size - offset < TLV_HEADER_BYTES) return null
            val n = u16(bytes, offset)
            if (n < TLV_HEADER_BYTES || n > bytes.size - offset) return null
            val paramId = u16(bytes, offset + 2)
            parameters.add(Parameter(paramId, bytes.copyOfRange(offset + TLV_HEADER_BYTES, offset + n)))
            offset += n
            if (parameters.size > MAX_MESSAGE_BYTES / TLV_HEADER_BYTES) return null
        }
        return Message(id, parameters)
    }

    /** All bytes in nested fields remain opaque, including Wi-Fi credentials. */
    fun summarize(message: Message): RedactedSummary = RedactedSummary(
        message.id, classify(message.id),
        message.parameters.map { it.id to it.value.size }
    )

    /** A 0x4301 message without parameter structure is not proof of Wi-Fi negotiation. */
    fun isStructurallyValidStartSession(message: Message): Boolean =
        message.id == CARPLAY_START_SESSION && message.parameters.isNotEmpty()

    private fun put16(output: ByteArray, at: Int, value: Int) {
        output[at] = (value ushr 8).toByte()
        output[at + 1] = value.toByte()
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
}
