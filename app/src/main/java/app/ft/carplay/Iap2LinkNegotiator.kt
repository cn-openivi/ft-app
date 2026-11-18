package app.ft.carplay

/**
 * Independently authored, bounded iAP2 LINK SYN exchange.
 *
 * A physical already-paired accessory sends a LINK SYN (session 0) declaring
 * link parameters and its actual session IDs. We answer with SYN+ACK reflecting
 * those parameters. The accessory's real ACK, with our sequence number, marks
 * LINK_NEGOTIATED only. No identification, MFi auth, Wi-Fi or AirPlay is inferred.
 *
 * This class is never wired into CarConnection auto-start. The physical probe
 * may call it only after an owner-initiated, authenticated Bluetooth bond check.
 */
internal class Iap2LinkNegotiator {
    enum class Phase { AWAITING_ACCESSORY_SYN, AWAITING_ACCESSORY_ACK, LINK_NEGOTIATED, REJECTED }
    enum class Failure {
        NONE, NOT_SYN, UNSUPPORTED_SYN, MISSING_CONTROL_SESSION,
        WRONG_ACK, OUT_OF_ORDER
    }
    data class Session(val id: Int, val type: Int, val version: Int)

    data class Step(
        val phase: Phase,
        val failure: Failure = Failure.NONE,
        /** Send over an already authenticated physical RFCOMM socket only. */
        val reply: ByteArray? = null
    )

    var phase: Phase = Phase.AWAITING_ACCESSORY_SYN
        private set
    var sessions: List<Session> = emptyList()
        private set

    // A real sender may start at any sequence number. A constant local initial
    // number is not a device identity, security credential or fake response.
    private val senderInitialSequence = 0x9d

    fun onRealLinkFrame(packet: Iap2LinkFrameCodec.Frame): Step = when (phase) {
        Phase.AWAITING_ACCESSORY_SYN -> acceptSyn(packet)
        Phase.AWAITING_ACCESSORY_ACK -> acceptAck(packet)
        Phase.LINK_NEGOTIATED, Phase.REJECTED -> Step(phase, Failure.OUT_OF_ORDER)
    }

    fun reset() {
        phase = Phase.AWAITING_ACCESSORY_SYN
        sessions = emptyList()
    }

    private fun acceptSyn(packet: Iap2LinkFrameCodec.Frame): Step {
        // 0x80 SYN bit, 0x40 ACK bit; LINK session must be 0.
        if (packet.control and 0x80 == 0 || packet.control and 0x40 != 0 ||
            packet.session != 0) return reject(Failure.NOT_SYN)

        val p = packet.payload
        if (p.size < 13 || (p.size - 10) % 3 != 0 ||
            (p[0].toInt() and 255) != 1 || (p[1].toInt() and 255) == 0 ||
            read16(p, 2) < 64) return reject(Failure.UNSUPPORTED_SYN)

        // Only the real head unit chooses session IDs. No hardcoded control=2.
        val observed = ArrayList<Session>()
        val used = HashSet<Int>()
        for (offset in 10 until p.size step 3) {
            val id = p[offset].toInt() and 255
            val type = p[offset + 1].toInt() and 255
            val version = p[offset + 2].toInt() and 255
            if (id == 0 || !used.add(id)) return reject(Failure.UNSUPPORTED_SYN)
            observed.add(Session(id, type, version))
        }
        if (observed.none { it.type == 0 }) return reject(Failure.MISSING_CONTROL_SESSION)
        sessions = observed
        phase = Phase.AWAITING_ACCESSORY_ACK
        return Step(
            phase,
            reply = Iap2LinkFrameCodec.encode(
                control = 0xc0, sequence = senderInitialSequence,
                acknowledgement = packet.sequence, session = 0, payload = p
            )
        )
    }

    private fun acceptAck(packet: Iap2LinkFrameCodec.Frame): Step {
        if (packet.control and 0x80 != 0 ||
            packet.control and 0x40 == 0 ||
            packet.session != 0 || packet.acknowledgement != senderInitialSequence ||
            packet.payload.isNotEmpty()) return reject(Failure.WRONG_ACK)
        phase = Phase.LINK_NEGOTIATED
        return Step(phase)
    }

    private fun reject(reason: Failure): Step {
        phase = Phase.REJECTED
        return Step(phase, reason)
    }

    private fun read16(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 255) shl 8) or (bytes[off + 1].toInt() and 255)
}
