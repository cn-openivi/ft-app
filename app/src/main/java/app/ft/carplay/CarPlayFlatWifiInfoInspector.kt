package app.ft.carplay

/**
 * FT200.2 passive, redacted flat Wi-Fi info (0x5703) structure inspection.
 * Runs on genuinely observed peer CONTROL bytes but does not read them as
 * authorized Wi-Fi credentials, connect to Android Wi-Fi, or prove CarPlay.
 */
internal object CarPlayFlatWifiInfoInspector {
    enum class Shape {
        NOT_FLAT_REPLY, MISSING_CREDENTIAL_FIELDS, UNKNOWN_OR_DUPLICATE_FIELDS,
        INVALID_FIELD_ENVELOPE, STRUCTURE_OBSERVED_ONLY
    }
    data class Summary(val shape: Shape, val fieldCount: Int) {
        override fun toString() = "CarPlayFlatWifiShape(shape=$shape, fields=$fieldCount, credentials redacted)"
    }

    fun inspect(message: Iap2ControlSessionCodec.Message): Summary {
        if (message.id != Iap2ControlSessionCodec.ACCESSORY_WIFI_CONFIG)
            return Summary(Shape.NOT_FLAT_REPLY, 0)
        val ps = message.parameters
        if (ps.map { it.id }.distinct().size != ps.size ||
            ps.any { it.id !in 1..3 })
            return Summary(Shape.UNKNOWN_OR_DUPLICATE_FIELDS, ps.size)
        val ssid = ps.singleOrNull { it.id == 1 }?.value
        val pass = ps.singleOrNull { it.id == 2 }?.value
        if (ssid == null || pass == null)
            return Summary(Shape.MISSING_CREDENTIAL_FIELDS, ps.size)
        // This diagnostic never consumes BSSID because its Toyota wire
        // semantics have not been verified, nor returns any secret bytes.
        if (ps.any { it.id == 3 })
            return Summary(Shape.UNKNOWN_OR_DUPLICATE_FIELDS, ps.size)
        if (ssid.size !in 2..33 || pass.size !in 9..64 ||
            ssid.last() != 0.toByte() || pass.last() != 0.toByte() ||
            ssid.dropLast(1).contains(0.toByte()) ||
            pass.dropLast(1).any { (it.toInt() and 255) !in 32..126 })
            return Summary(Shape.INVALID_FIELD_ENVELOPE, ps.size)
        return Summary(Shape.STRUCTURE_OBSERVED_ONLY, ps.size)
    }
}
