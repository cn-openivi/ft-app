package app.ft.carplay

/**
 * Pure, bounded stop-and-wait bookkeeping for an already negotiated iAP2 LINK.
 *
 * A correctly framed packet is NOT proof of Apple authentication or legitimate
 * CarPlay. This component never opens sockets or sends on its own. Link/window
 * options beyond the one-outstanding-packet profile are explicitly unsupported
 * until actual Toyota evidence is available.
 */
internal class Iap2ReliableLinkWindow(
    private val confirmed: Iap2LinkNegotiator.ConfirmedLink,
    private val maxRetries: Int = 3
) {
    init {
        require(maxRetries in 0..30)
        require(confirmed.nextPhoneSequence in 0..255)
        require(confirmed.lastAccessorySequence in 0..255)
        require(confirmed.sessions.map { it.id }.distinct().size ==
            confirmed.sessions.size)
    }

    enum class Status {
        ACCEPTED_IN_ORDER, DUPLICATE, OUT_OF_ORDER, EMPTY_ACK,
        UNKNOWN_SESSION, MALFORMED_HEADER
    }

    data class Incoming(
        val status: Status,
        val ackOfOutstanding: Boolean = false,
        /** Expose payload ONLY to the in-memory CONTROL parser, never to logs. */
        val payload: ByteArray? = null,
        val sessionId: Int? = null,
        val cumulativeAck: Int? = null
    )

    enum class SendStatus {
        READY, BUSY, INVALID_SESSION, INVALID_FRAME,
        RETRY, RETRY_EXHAUSTED, NOTHING_PENDING,
        ACK_READY, ACK_NOT_REQUIRED, WAITING_TIMEOUT
    }

    data class Outgoing(
        val status: SendStatus,
        /** Pass directly to caller-owned socket, never persist in diagnostics. */
        val wire: ByteArray? = null
    )

    private data class Outstanding(
        val sequence: Int,
        val wire: ByteArray,
        var retries: Int
    )

    private var receivedSequence = confirmed.lastAccessorySequence
    private var sendingSequence = confirmed.nextPhoneSequence
    private var outstanding: Outstanding? = null

    @Synchronized
    fun pendingCount(): Int = if (outstanding == null) 0 else 1

    /**
     * Prepare a single encrypted-or-clear higher-level CONTROL payload only
     * after a *separately verified* authentication/feature state. There is no
     * implicit CarPlay activation here. Exactly one packet may be in flight.
     */
    @Synchronized
    fun prepareData(sessionId: Int, payload: ByteArray): Outgoing {
        if (outstanding != null) return Outgoing(SendStatus.BUSY)
        if (confirmed.sessions.none { it.id == sessionId }) {
            return Outgoing(SendStatus.INVALID_SESSION)
        }
        if (payload.isEmpty() ||
            payload.size > Iap2LinkFrameCodec.MAX_FRAME_BYTES -
                Iap2LinkFrameCodec.HEADER_BYTES - 1) {
            return Outgoing(SendStatus.INVALID_FRAME)
        }
        val wire = Iap2LinkFrameCodec.encode(
            control = 0x40,
            sequence = sendingSequence,
            acknowledgement = receivedSequence,
            session = sessionId,
            payload = payload
        )
        outstanding = Outstanding(sendingSequence, wire, 0)
        sendingSequence = (sendingSequence + 1) and 0xff
        return Outgoing(SendStatus.READY, wire.copyOf())
    }

    /**
     * Never overwrite an outstanding frame, and NEVER mutate the retry bytes.
     * Caller chooses timeout policy; this class never starts a background job.
     */
    @Synchronized
    fun retryOutstanding(): Outgoing {
        val current = outstanding ?: return Outgoing(SendStatus.NOTHING_PENDING)
        if (current.retries >= maxRetries) {
            return Outgoing(SendStatus.RETRY_EXHAUSTED)
        }
        current.retries++
        return Outgoing(SendStatus.RETRY, current.wire.copyOf())
    }

    /**
     * Acknowledge only the exact outstanding send sequence. Receive sequence is
     * modulo-256, including 255 -> 0 wrap. Repeated frames are not delivered a
     * second time and gaps cannot advance the expected sequence.
     */
    @Synchronized
    fun observe(frame: Iap2LinkFrameCodec.Frame): Incoming {
        if (frame.sequence !in 0..255 ||
            frame.acknowledgement !in 0..255 ||
            frame.control !in 0..255 ||
            frame.control != 0x40) {
            return Incoming(Status.MALFORMED_HEADER)
        }
        // A peer cannot acknowledge our last outbound data by inventing a
        // foreign session ID. Empty LINK ACKs may use session 0.
        val knownSession = confirmed.sessions.any { it.id == frame.session }
        if (!knownSession && (frame.payload.isNotEmpty() || frame.session != 0)) {
            return Incoming(Status.UNKNOWN_SESSION, cumulativeAck = receivedSequence)
        }
        val matched = outstanding?.sequence == frame.acknowledgement
        if (matched) outstanding = null

        if (frame.payload.isEmpty()) {
            return Incoming(Status.EMPTY_ACK, ackOfOutstanding = matched,
                cumulativeAck = receivedSequence)
        }
        val distance = (frame.sequence - receivedSequence) and 0xff
        return when (distance) {
            1 -> {
                receivedSequence = frame.sequence
                Incoming(Status.ACCEPTED_IN_ORDER, matched,
                    frame.payload.copyOf(), frame.session, receivedSequence)
            }
            0 -> Incoming(Status.DUPLICATE, matched, cumulativeAck = receivedSequence)
            else -> Incoming(Status.OUT_OF_ORDER, matched,
                cumulativeAck = receivedSequence)
        }
    }

    /**
     * Build a cumulative pure ACK only for a decoded inbound DATA frame.
     * Duplicates and gaps re-advertise the last IN-ORDER receive sequence.
     * Empty ACK frames never elicit replies (no ACK storm).
     *
     * This stop-and-wait sequence choice is source-only until final Toyota
     * validation. Pure ACK consumes no DATA sequence; it uses the last
     * locally sent sequence, including negotiated SYN+ACK.
     */
    @Synchronized
    fun prepareCumulativeAck(incoming: Incoming): Outgoing {
        val needAck = incoming.status == Status.ACCEPTED_IN_ORDER ||
            incoming.status == Status.DUPLICATE ||
            incoming.status == Status.OUT_OF_ORDER
        if (!needAck || incoming.cumulativeAck != receivedSequence)
            return Outgoing(SendStatus.ACK_NOT_REQUIRED)
        return Outgoing(
            SendStatus.ACK_READY,
            Iap2LinkFrameCodec.encode(
                control = 0x40,
                sequence = (sendingSequence - 1) and 0xff,
                acknowledgement = receivedSequence,
                session = 0
            )
        )
    }

    @Synchronized
    fun reset() {
        outstanding = null
        receivedSequence = confirmed.lastAccessorySequence
        sendingSequence = confirmed.nextPhoneSequence
    }
}
