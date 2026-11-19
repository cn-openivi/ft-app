package app.ft.carplay

import java.nio.charset.StandardCharsets

/**
 * FT200.2 outgoing Wi-Fi-control request preparation on a physical iAP2 link.
 * Source-only: no socket send, no fictitious MFi authorization or Bluetooth
 * address. The caller must independently prove actual Toyota identification,
 * link CONTROL session and iAP2/auth chain on the SAME session generation.
 *
 * A prepared request or recognized reply is never CarPlay ACTIVE.
 */
internal class CarPlayWifiRequestSession {
    enum class Result {
        PREPARED_NOT_SENT, INVALID_GENERATION, UNAUTHENTICATED,
        HU_IDENTITY_UNVERIFIED, DIALECT_UNSUPPORTED,
        VERIFIED_LOCAL_BLUETOOTH_IDENTITY_REQUIRED,
        REQUEST_ALREADY_PREPARED, REPLY_NOT_EXPECTED,
        REPLY_ID_MISMATCH, REPLY_UNAUTHENTICATED,
        REPLY_OBSERVED_UNVERIFIED
    }

    class Proposal(
        val result: Result,
        val message: Iap2ControlSessionCodec.Message? = null
    ) {
        override fun toString() = "CarPlayWifiRequestProposal($result, sensitive values redacted)"
    }

    private var largestGeneration = 0L
    private var activeGeneration: Long? = null
    private var awaitedReplyId: Int? = null

    @Synchronized
    fun prepare(
        generation: Long,
        dialect: CarPlayWifiDialectPolicy.Selection,
        realIap2Authenticated: Boolean,
        sameToyotaIdentityVerified: Boolean,
        actualPhoneBluetoothIdentityVerified: Boolean,
        actualPhoneBluetoothAddress: String?
    ): Proposal {
        if (generation <= 0L || generation < largestGeneration)
            return Proposal(Result.INVALID_GENERATION)
        if (generation == activeGeneration) return Proposal(Result.REQUEST_ALREADY_PREPARED)
        if (generation == largestGeneration) return Proposal(Result.INVALID_GENERATION)
        // Every new attempt invalidates old requests, including failed attempts.
        largestGeneration = generation
        activeGeneration = null
        awaitedReplyId = null
        if (!realIap2Authenticated) return Proposal(Result.UNAUTHENTICATED)
        if (!sameToyotaIdentityVerified) return Proposal(Result.HU_IDENTITY_UNVERIFIED)
        val request = when (dialect.status) {
            CarPlayWifiDialectPolicy.Status.FLAT_5702_5703_HINT ->
                Iap2ControlSessionCodec.Message(
                    Iap2ControlSessionCodec.REQUEST_ACCESSORY_WIFI_CONFIG, emptyList())
            CarPlayWifiDialectPolicy.Status.GROUPED_4300_4301_HINT -> {
                val address = actualPhoneBluetoothAddress
                if (!actualPhoneBluetoothIdentityVerified ||
                    !isConcreteRealPhoneBluetoothAddress(address))
                    return Proposal(Result.VERIFIED_LOCAL_BLUETOOTH_IDENTITY_REQUIRED)
                val attrsWired = tlv(0, byteArrayOf(0))
                val attrsWireless = tlv(0, byteArrayOf(1)) +
                    tlv(1, (address!! + "\u0000").toByteArray(StandardCharsets.US_ASCII))
                Iap2ControlSessionCodec.Message(
                    Iap2ControlSessionCodec.CARPLAY_AVAILABILITY,
                    listOf(
                        Iap2ControlSessionCodec.Parameter(0, attrsWired),
                        Iap2ControlSessionCodec.Parameter(1, attrsWireless)
                    )
                )
            }
            else -> return Proposal(Result.DIALECT_UNSUPPORTED)
        }
        if (dialect.phoneRequestMessageId != request.id ||
            dialect.expectedAccessoryReplyId !in listOf(
                Iap2ControlSessionCodec.CARPLAY_START_SESSION,
                Iap2ControlSessionCodec.ACCESSORY_WIFI_CONFIG))
            return Proposal(Result.DIALECT_UNSUPPORTED)
        activeGeneration = generation
        awaitedReplyId = dialect.expectedAccessoryReplyId
        return Proposal(Result.PREPARED_NOT_SENT, request)
    }

    @Synchronized
    fun observeReply(
        generation: Long,
        realIap2Authenticated: Boolean,
        message: Iap2ControlSessionCodec.Message
    ): Result {
        if (generation != activeGeneration || awaitedReplyId == null)
            return Result.REPLY_NOT_EXPECTED
        if (!realIap2Authenticated) {
            activeGeneration = null
            awaitedReplyId = null
            return Result.REPLY_UNAUTHENTICATED
        }
        if (message.id != awaitedReplyId)
            return Result.REPLY_ID_MISMATCH
        // A reply ID is just source-only evidence; content, passwords, network,
        // real AirPlay authentication and field proof require separate gates.
        activeGeneration = null
        awaitedReplyId = null
        return Result.REPLY_OBSERVED_UNVERIFIED
    }

    @Synchronized
    fun onLinkLost(generation: Long) {
        if (generation == activeGeneration) {
            activeGeneration = null
            awaitedReplyId = null
        }
    }

    private fun isConcreteRealPhoneBluetoothAddress(value: String?): Boolean {
        if (value == null ||
            !Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}").matches(value)) return false
        val normalized = value.uppercase()
        return normalized != "02:00:00:00:00:00" &&
            normalized != "02:00:00:00:00:01" &&
            normalized != "00:00:00:00:00:00" &&
            normalized != "FF:FF:FF:FF:FF:FF"
    }

    private fun tlv(id: Int, bytes: ByteArray): ByteArray {
        val n = bytes.size + 4
        require(n <= 65535)
        return byteArrayOf(
            (n ushr 8).toByte(), n.toByte(),
            (id ushr 8).toByte(), id.toByte()
        ) + bytes
    }
}
