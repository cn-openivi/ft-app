package app.ft.carplay

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * FT200.2 read-only accessory Wi-Fi configuration 0x5703 (flat dialect).
 *
 * The flat dialect does not contain an independently verified WPA security
 * enum. An authenticated and independently security-approved Toyota profile
 * is mandatory. Observed credentials alone never initiate Wi-Fi, create a
 * second Bluetooth bond, or mark CarPlay connected.
 */
internal object CarPlayFlatWifiInfoExtractor {
    enum class Status {
        AUTHENTICATION_REQUIRED, TOYOTA_PROFILE_UNVERIFIED,
        WRONG_DIALECT_OR_MESSAGE, INVALID_OR_DUPLICATE_FIELDS,
        MISSING_WIFI_FIELDS, INVALID_CREDENTIALS, CANDIDATE_NOT_CONNECTED
    }

    class Candidate internal constructor(val ssid: String, val passphrase: String) {
        override fun toString() = "FlatWifiCandidate(credentials redacted)"
    }
    class Result(val status: Status, val candidate: Candidate? = null) {
        override fun toString() = "FlatWifiInfoResult($status, credentials redacted)"
    }

    fun extract(
        reply: Iap2ControlSessionCodec.Message,
        advertisedDialect: CarPlayWifiDialectPolicy.Selection,
        realIap2Authenticated: Boolean,
        independentlyVerifiedToyotaWpa2Profile: Boolean
    ): Result {
        if (!realIap2Authenticated) return Result(Status.AUTHENTICATION_REQUIRED)
        if (!independentlyVerifiedToyotaWpa2Profile)
            return Result(Status.TOYOTA_PROFILE_UNVERIFIED)
        if (advertisedDialect.status !=
            CarPlayWifiDialectPolicy.Status.FLAT_5702_5703_HINT ||
            advertisedDialect.expectedAccessoryReplyId !=
            Iap2ControlSessionCodec.ACCESSORY_WIFI_CONFIG ||
            reply.id != Iap2ControlSessionCodec.ACCESSORY_WIFI_CONFIG)
            return Result(Status.WRONG_DIALECT_OR_MESSAGE)
        if (reply.parameters.size !in 2..3 ||
            reply.parameters.map { it.id }.distinct().size != reply.parameters.size ||
            reply.parameters.any { it.id !in 1..3 })
            return Result(Status.INVALID_OR_DUPLICATE_FIELDS)
        // BSSID is optional and opaque. If present, its precise format must be
        // verified against the Toyota runtime before ever consuming it.
        if (reply.parameters.any { it.id == 3 })
            return Result(Status.INVALID_OR_DUPLICATE_FIELDS)
        val ssidWire = reply.parameters.singleOrNull { it.id == 1 }?.value
        val passWire = reply.parameters.singleOrNull { it.id == 2 }?.value
        if (ssidWire == null || passWire == null)
            return Result(Status.MISSING_WIFI_FIELDS)
        val ssid = decodeNullTerminatedUtf8(ssidWire)
        val pass = decodeNullTerminatedAscii(passWire)
        if (ssid == null || pass == null ||
            ssid.toByteArray(StandardCharsets.UTF_8).size !in 1..32 ||
            pass.length !in 8..63 ||
            ssid.any { Character.isISOControl(it) || it == '\u0000' } ||
            pass.any { it.code !in 32..126 })
            return Result(Status.INVALID_CREDENTIALS)
        return Result(Status.CANDIDATE_NOT_CONNECTED, Candidate(ssid, pass))
    }

    private fun decodeNullTerminatedUtf8(data: ByteArray): String? {
        if (data.size !in 2..33 || data.last() != 0.toByte() ||
            data.dropLast(1).any { it == 0.toByte() }) return null
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, 0, data.size - 1)).toString()
        } catch (_: CharacterCodingException) { null }
    }

    private fun decodeNullTerminatedAscii(data: ByteArray): String? {
        if (data.size !in 9..64 || data.last() != 0.toByte()) return null
        if (data.dropLast(1).any { (it.toInt() and 255) !in 32..126 })
            return null
        return String(data, 0, data.size - 1, StandardCharsets.US_ASCII)
    }
}
