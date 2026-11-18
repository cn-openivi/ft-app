package app.ft.carplay

import java.nio.charset.StandardCharsets

/**
 * Decodes only the Wi-Fi subset of inbound CarPlayStartSession 0x4301,
 * subject to an independently VERIFIED iAP2/MFi session and approved on-wire
 * security enum. The Toyota enum meanings are not assumed from another OEM.
 *
 * Credentials exist in memory only as long as the Android network request
 * needs them. NEVER log or persist Candidate/SSID/password, including error.
 */
internal object CarPlayWifiStartExtractor {
    enum class Status {
        AUTHENTICATION_REQUIRED, INVALID_START, DUPLICATE_OR_UNKNOWN_FIELD,
        MISSING_WIFI_FIELDS, UNAPPROVED_WIRE_SECURITY, INVALID_CREDENTIALS,
        CANDIDATE_ONLY
    }
    class Candidate internal constructor(
        val ssid: String,
        val passphrase: String,
        val wireSecurityType: Int
    ) {
        override fun toString() = "WifiCandidate(credentials redacted)"
    }
    class Result(val status: Status, val candidate: Candidate? = null) {
        override fun toString() = "WifiExtraction(status=" + status + ", redacted)"
    }

    fun extract(
        message: Iap2ControlSessionCodec.Message,
        realIap2AuthenticationVerified: Boolean,
        approvedWpa2SecurityType: Int?
    ): Result {
        if (!realIap2AuthenticationVerified)
            return Result(Status.AUTHENTICATION_REQUIRED)
        if (approvedWpa2SecurityType !in 0..255)
            return Result(Status.UNAPPROVED_WIRE_SECURITY)
        val summary = Iap2CarPlayStartSchema.inspect(message)
        if (summary.result != Iap2CarPlayStartSchema.Result.STRUCTURE_OBSERVED)
            return Result(Status.INVALID_START)
        if (summary.unknownCount != 0 || summary.duplicateScalarCount != 0)
            return Result(Status.DUPLICATE_OR_UNKNOWN_FIELD)
        val wifi = message.parameters.filter { it.id == 1 }
        if (wifi.size != 1) return Result(Status.MISSING_WIFI_FIELDS)
        val nested = parseNested(wifi.single().value) ?: return Result(Status.INVALID_START)
        val ssid = nested.singleOrNull { it.first == 0 }?.second
        val secret = nested.singleOrNull { it.first == 1 }?.second
        val typeBytes = nested.singleOrNull { it.first == 4 }?.second
        if (ssid == null || secret == null || typeBytes?.size != 1)
            return Result(Status.MISSING_WIFI_FIELDS)
        val wireType = typeBytes[0].toInt() and 255
        if (wireType != approvedWpa2SecurityType)
            return Result(Status.UNAPPROVED_WIRE_SECURITY)
        // Strict C-string was already checked by the schema. Avoid admitting
        // wildcard SSIDs/invalid WPA2 secrets or silent encoding conversion.
        val ssidBytes = ssid.copyOf(ssid.size - 1)
        val secretBytes = secret.copyOf(secret.size - 1)
        if (ssidBytes.size !in 1..32 || secretBytes.size !in 8..63 ||
            ssidBytes.any { val c = it.toInt() and 255; c < 32 || c == 127 } ||
            secretBytes.any { val c = it.toInt() and 255; c !in 32..126 })
            return Result(Status.INVALID_CREDENTIALS)
        return Result(Status.CANDIDATE_ONLY, Candidate(
            String(ssidBytes, StandardCharsets.UTF_8),
            String(secretBytes, StandardCharsets.US_ASCII),
            wireType
        ))
    }

    private fun parseNested(bytes: ByteArray): List<Pair<Int, ByteArray>>? {
        val values = ArrayList<Pair<Int, ByteArray>>()
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < 4) return null
            val n = ((bytes[offset].toInt() and 255) shl 8) or
                (bytes[offset + 1].toInt() and 255)
            val id = ((bytes[offset + 2].toInt() and 255) shl 8) or
                (bytes[offset + 3].toInt() and 255)
            if (n < 4 || n > bytes.size - offset) return null
            values.add(id to bytes.copyOfRange(offset + 4, offset + n))
            if (values.size > 64) return null
            offset += n
        }
        return values
    }
}
