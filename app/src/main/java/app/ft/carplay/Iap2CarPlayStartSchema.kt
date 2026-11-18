package app.ft.carplay

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Clean-room, read-only validation of inbound CarPlayStartSession (0x4301).
 *
 * The known two-level TLV paths are protocol-research evidence from an
 * unrelated accessory profile, NOT confirmed on FAW Toyota XA60.
 * Valid structure is never sufficient for Wi-Fi handover, MFi or CarPlay ACTIVE.
 *
 * No SSID, password, device ID, public key or endpoint value is retained here.
 * Only field names/counts appear in this redacted result.
 */
internal object Iap2CarPlayStartSchema {
    enum class Result {
        NOT_START_SESSION,
        EMPTY,
        MALFORMED_NESTED_TLV,
        MALFORMED_FIELD,
        UNKNOWN_ONLY,
        STRUCTURE_OBSERVED
    }

    data class Summary(
        val result: Result,
        val fieldNames: Set<String> = emptySet(),
        val unknownCount: Int = 0,
        val duplicateScalarCount: Int = 0,
        val usbIpCount: Int = 0,
        val wifiIpCount: Int = 0
    ) {
        // Never store values: data-class toString() is safe for field diagnostics.
        val hasUsableKnownFields: Boolean
            get() = result == Result.STRUCTURE_OBSERVED && fieldNames.isNotEmpty()
    }

    private enum class WireType { STRING, U8, U32, BOOL }
    private data class Definition(val field: String, val type: WireType, val array: Boolean = false)

    private val top = mapOf(
        2 to Definition("port", WireType.U32),
        3 to Definition("deviceID", WireType.STRING),
        4 to Definition("pubKey", WireType.STRING),
        5 to Definition("srcVer", WireType.STRING),
        6 to Definition("sdkVer", WireType.STRING),
        8 to Definition("mutualAuth", WireType.BOOL)
    )
    private val usb = mapOf(0 to Definition("usbIP", WireType.STRING, array = true))
    private val wifi = mapOf(
        0 to Definition("ssid", WireType.STRING),
        1 to Definition("pass", WireType.STRING),
        2 to Definition("channel", WireType.U8),
        3 to Definition("wifiIP", WireType.STRING, array = true),
        4 to Definition("securityType", WireType.U8)
    )
    private val asset = mapOf(
        0 to Definition("assetID", WireType.STRING),
        1 to Definition("assetVer", WireType.U32)
    )

    fun inspect(message: Iap2ControlSessionCodec.Message): Summary {
        if (message.id != Iap2ControlSessionCodec.CARPLAY_START_SESSION) {
            return Summary(Result.NOT_START_SESSION)
        }
        if (message.parameters.isEmpty()) return Summary(Result.EMPTY)

        val observed = linkedSetOf<String>()
        var unknown = 0
        var duplicates = 0
        var usbIp = 0
        var wifiIp = 0

        fun record(def: Definition, raw: ByteArray): Boolean {
            if (!validValue(def.type, raw)) return false
            if (!observed.add(def.field) && !def.array) duplicates++
            if (def.field == "usbIP") usbIp++
            if (def.field == "wifiIP") wifiIp++
            return true
        }

        for (param in message.parameters) {
            when (param.id) {
                0, 1, 7 -> {
                    // These are nested TLV GROUPS, not plain 14 flat parameters.
                    val items = nested(param.value) ?: return Summary(Result.MALFORMED_NESTED_TLV)
                    val definitions = when (param.id) {
                        0 -> usb
                        1 -> wifi
                        else -> asset
                    }
                    for (item in items) {
                        val def = definitions[item.id]
                        if (def == null) {
                            unknown++
                        } else if (!record(def, item.value)) {
                            return Summary(Result.MALFORMED_FIELD)
                        }
                    }
                }
                else -> {
                    val def = top[param.id]
                    if (def == null) {
                        unknown++
                    } else if (!record(def, param.value)) {
                        return Summary(Result.MALFORMED_FIELD)
                    }
                }
            }
        }

        return Summary(
            result = if (observed.isEmpty()) Result.UNKNOWN_ONLY else Result.STRUCTURE_OBSERVED,
            fieldNames = observed,
            unknownCount = unknown,
            duplicateScalarCount = duplicates,
            usbIpCount = usbIp,
            wifiIpCount = wifiIp
        )
    }

    private fun validValue(type: WireType, value: ByteArray): Boolean = when (type) {
        WireType.U8 -> value.size == 1
        WireType.U32 -> value.size == 4
        WireType.BOOL -> value.size == 1 && value[0].toInt() in 0..1
        WireType.STRING -> {
            if (value.isEmpty() || value.last().toInt() != 0 ||
                value.dropLast(1).any { it.toInt() == 0 }) {
                false
            } else {
                // Reject malformed UTF-8 rather than silently changing a
                // credential, IP or identifier via replacement characters.
                try {
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(value, 0, value.size - 1))
                    true
                } catch (_: CharacterCodingException) {
                    false
                }
            }
        }
    }

    private fun nested(payload: ByteArray): List<Iap2ControlSessionCodec.Parameter>? {
        val result = ArrayList<Iap2ControlSessionCodec.Parameter>()
        var offset = 0
        while (offset < payload.size) {
            if (payload.size - offset < 4) return null
            val len = u16(payload, offset)
            if (len < 4 || len > payload.size - offset) return null
            val id = u16(payload, offset + 2)
            result.add(Iap2ControlSessionCodec.Parameter(id,
                payload.copyOfRange(offset + 4, offset + len)))
            offset += len
        }
        return result
    }

    private fun u16(bytes: ByteArray, at: Int) =
        ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
}
