package app.ft.carplay

/**
 * FT200.2 opt-in, original implementation of the initial phone-side
 * IdentificationRequest (0x1D00) following a REAL iAP2 LINK ACK.
 *
 * Public research of phone-side iAP2 has shown an identification-first flow
 * for a negotiated CONTROL session version 2. This is a diagnostic hypothesis
 * for FAW Toyota, NOT proof that the target HU supports this ordering.
 *
 * It sends NO MFi authentication claim, identification acceptance, CarPlay
 * availability, invented UUID, Wi-Fi credentials or AirPlay setup data.
 */
internal class Iap2PhoneIdentificationProbe {
    enum class Result {
        DIAGNOSTIC_DISABLED,
        LINK_NOT_CONFIRMED,
        MISSING_OR_AMBIGUOUS_CONTROL,
        UNSUPPORTED_CONTROL_VERSION,
        REQUEST_PREPARED,
        REQUEST_ALREADY_PREPARED
    }

    data class Proposal(
        val result: Result,
        /** For an owner-triggered, physically negotiated RFCOMM link only. */
        val frame: ByteArray? = null,
        val controlSessionId: Int? = null,
        val negotiatedVersion: Int? = null
    )

    private var prepared = false

    fun reset() {
        prepared = false
    }

    /**
     * No sending is performed here. The caller must explicitly opt in before
     * writing to the existing, already bonded real Toyota Bluetooth socket.
     * A mismatched or unknown session version is not silently rewritten.
     */
    fun prepare(
        confirmed: Iap2LinkNegotiator.ConfirmedLink?,
        optIn: Boolean
    ): Proposal {
        if (!optIn) return Proposal(Result.DIAGNOSTIC_DISABLED)
        if (prepared) return Proposal(Result.REQUEST_ALREADY_PREPARED)
        if (confirmed == null) return Proposal(Result.LINK_NOT_CONFIRMED)
        val matching = confirmed.sessions.filter { it.type == 0 }
        if (matching.size != 1) return Proposal(Result.MISSING_OR_AMBIGUOUS_CONTROL)
        val control = matching.single()
        if (control.version != 2) {
            return Proposal(
                Result.UNSUPPORTED_CONTROL_VERSION,
                controlSessionId = control.id,
                negotiatedVersion = control.version
            )
        }
        if (control.id !in 1..255 ||
            confirmed.nextPhoneSequence !in 0..255 ||
            confirmed.lastAccessorySequence !in 0..255
        ) return Proposal(Result.LINK_NOT_CONFIRMED)

        // Format is a standard iAP2 CONTROL message with no TLV parameters.
        // ACK and session numbers originate in the actual negotiated LINK.
        val request = Iap2ControlSessionCodec.encode(
            Iap2ControlSessionCodec.Message(0x1D00, emptyList())
        )
        val wire = Iap2LinkFrameCodec.encode(
            control = 0x40,
            sequence = confirmed.nextPhoneSequence,
            acknowledgement = confirmed.lastAccessorySequence,
            session = control.id,
            payload = request
        )
        // Exactly once per physical session; even a failed I/O does not cause
        // a blind duplicate. A new Bluetooth session creates a fresh instance.
        prepared = true
        return Proposal(
            Result.REQUEST_PREPARED,
            frame = wire,
            controlSessionId = control.id,
            negotiatedVersion = control.version
        )
    }
}
