package app.ft.carplay

/**
 * FT200.2: read actual accessory IdentificationInformation 0x1D01 message
 * capability lists before choosing a Wi-Fi setup dialect. Clean-room protocol
 * observation only: no iAP2/MFi trust, phone MAC spoofing, network operations or
 * implicit Apple CarPlay availability is produced.
 *
 * Public phone-side protocol research describes mutually distinct exchanges:
 * 0x4300 -> 0x4301 with grouped Wi-Fi TLV, 0x5702 -> 0x5703 with
 * flat Wi-Fi TLV. The real FAW Toyota preference is UNKNOWN until observed.
 */
internal object CarPlayWifiDialectPolicy {
    enum class Status {
        NOT_IDENTIFICATION, MESSAGE_LISTS_MISSING,
        MALFORMED_OR_DUPLICATED_LISTS,
        GROUPED_4300_4301_HINT, FLAT_5702_5703_HINT,
        AMBIGUOUS_BOTH_DIALECTS, NO_COMPLETE_ADVERTISED_PAIR
    }

    /** Both fields are only protocol message IDs; no secrets or device IDs. */
    class Selection(
        val status: Status,
        val phoneRequestMessageId: Int? = null,
        val expectedAccessoryReplyId: Int? = null
    ) {
        override fun toString(): String = "CarPlayWifiDialect(status=$status, not authenticated)"
    }

    fun inspect(message: Iap2ControlSessionCodec.Message): Selection {
        if (message.id != Iap2IdentificationInfoInspector.IDENTIFICATION_INFORMATION)
            return Selection(Status.NOT_IDENTIFICATION)
        // 0x0006: messages sent by accessory, 0x0007: messages accepted from
        // the phone. Duplicates and malformed byte pairs remain UNKNOWN.
        val sentFields = message.parameters.filter { it.id == 6 }
        val receivedFields = message.parameters.filter { it.id == 7 }
        if (sentFields.isEmpty() || receivedFields.isEmpty())
            return Selection(Status.MESSAGE_LISTS_MISSING)
        if (sentFields.size != 1 || receivedFields.size != 1)
            return Selection(Status.MALFORMED_OR_DUPLICATED_LISTS)
        val sent = parseBigEndianIds(sentFields.single().value)
            ?: return Selection(Status.MALFORMED_OR_DUPLICATED_LISTS)
        val received = parseBigEndianIds(receivedFields.single().value)
            ?: return Selection(Status.MALFORMED_OR_DUPLICATED_LISTS)
        val grouped = Iap2ControlSessionCodec.CARPLAY_AVAILABILITY in received &&
            Iap2ControlSessionCodec.CARPLAY_START_SESSION in sent
        val flat = Iap2ControlSessionCodec.REQUEST_ACCESSORY_WIFI_CONFIG in received &&
            Iap2ControlSessionCodec.ACCESSORY_WIFI_CONFIG in sent
        return when {
            grouped && flat -> Selection(Status.AMBIGUOUS_BOTH_DIALECTS)
            grouped -> Selection(Status.GROUPED_4300_4301_HINT,
                Iap2ControlSessionCodec.CARPLAY_AVAILABILITY,
                Iap2ControlSessionCodec.CARPLAY_START_SESSION)
            flat -> Selection(Status.FLAT_5702_5703_HINT,
                Iap2ControlSessionCodec.REQUEST_ACCESSORY_WIFI_CONFIG,
                Iap2ControlSessionCodec.ACCESSORY_WIFI_CONFIG)
            else -> Selection(Status.NO_COMPLETE_ADVERTISED_PAIR)
        }
    }

    private fun parseBigEndianIds(raw: ByteArray): Set<Int>? {
        if (raw.size !in 2..256 || raw.size % 2 != 0) return null
        val ids = linkedSetOf<Int>()
        for (at in raw.indices step 2) {
            val id = (raw[at].toInt() and 255) shl 8 or
                (raw[at + 1].toInt() and 255)
            if (!ids.add(id)) return null
        }
        return ids
    }
}
