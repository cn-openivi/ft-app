package app.ft.carplay

import java.security.SecureRandom

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
internal class Iap2LinkNegotiator(
    private val senderInitialSequence: Int = SecureRandom().nextInt(256)
) {
    init { require(senderInitialSequence in 0..255) }
    enum class Phase { AWAITING_ACCESSORY_SYN, AWAITING_ACCESSORY_ACK, LINK_NEGOTIATED, REJECTED }
    enum class Failure {
        NONE, NOT_SYN, UNSUPPORTED_SYN, MISSING_CONTROL_SESSION,
        WRONG_ACK, OUT_OF_ORDER
    }
    data class Session(val id: Int, val type: Int, val version: Int)

    /** Available ONLY after a genuine peer ACK of our SYN+ACK. */
    data class ConfirmedLink(
        val sessions: List<Session>,
        val nextPhoneSequence: Int,
        val lastAccessorySequence: Int
    )

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

    // A freshly selected sender sequence is link state, not a BT identity or
    // MFi credential. Injection permits reproducible wire-format unit tests.
    private var lastAccessorySequence: Int? = null

    /** No session ID, sequence or ACK is guessed before the physical LINK ACK. */
    fun confirmedLink(): ConfirmedLink? {
        if (phase != Phase.LINK_NEGOTIATED) return null
        val sequence = lastAccessorySequence ?: return null
        return ConfirmedLink(sessions.toList(), (senderInitialSequence + 1) and 0xff, sequence)
    }

    fun onRealLinkFrame(packet: Iap2LinkFrameCodec.Frame): Step = when (phase) {
        Phase.AWAITING_ACCESSORY_SYN -> acceptSyn(packet)
        Phase.AWAITING_ACCESSORY_ACK -> acceptAck(packet)
        Phase.LINK_NEGOTIATED, Phase.REJECTED -> Step(phase, Failure.OUT_OF_ORDER)
    }

    fun reset() {
        phase = Phase.AWAITING_ACCESSORY_SYN
        sessions = emptyList()
        lastAccessorySequence = null
    }

    private fun acceptSyn(packet: Iap2LinkFrameCodec.Frame): Step {
        // 0x80 SYN bit, 0x40 ACK bit; LINK session must be 0.
        if (packet.control and 0x80 == 0 || packet.control and 0x40 != 0 ||
            packet.session != 0) return reject(Failure.NOT_SYN)

        // v2+ has a different wire layout, including an extension count.
        // Never assume that session entries begin at byte 10 for all versions.
        val parsed = Iap2SynPayloadParser.parse(packet.payload)
            ?: return reject(Failure.UNSUPPORTED_SYN)
        val observed = parsed.sessions.map { Session(it.id, it.type, it.version) }
        if (observed.none { it.type == 0 }) return reject(Failure.MISSING_CONTROL_SESSION)
        if (observed.count { it.type == 0 } != 1 ||
            observed.map { it.type }.toSet().size != observed.size) {
            return reject(Failure.UNSUPPORTED_SYN)
        }
        sessions = observed
        phase = Phase.AWAITING_ACCESSORY_ACK
        return Step(
            phase,
            reply = Iap2LinkFrameCodec.encode(
                control = 0xc0, sequence = senderInitialSequence,
                acknowledgement = packet.sequence, session = 0,
                payload = packet.payload
            )
        )
    }

    private fun acceptAck(packet: Iap2LinkFrameCodec.Frame): Step {
        if (packet.control and 0x80 != 0 ||
            packet.control and 0x40 == 0 ||
            packet.session != 0 || packet.acknowledgement != senderInitialSequence ||
            packet.payload.isNotEmpty()) return reject(Failure.WRONG_ACK)
        lastAccessorySequence = packet.sequence
        phase = Phase.LINK_NEGOTIATED
        return Step(phase)
    }

    private fun reject(reason: Failure): Step {
        phase = Phase.REJECTED
        return Step(phase, reason)
    }

}
