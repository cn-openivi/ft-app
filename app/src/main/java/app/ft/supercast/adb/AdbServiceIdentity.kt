package app.ft.supercast.adb

/**
 * AOSP Wi-Fi ADB advertises a GUID-linked connect service. Only the GUID
 * returned in SPAKE2-authenticated PeerInfo is allowed to select a port.
 * A changing mDNS suffix/port is a locator and never an authorization token.
 */
internal object AdbServiceIdentity {
    fun isValidGuid(guid: String): Boolean =
        guid.length in 8..128 &&
            guid.all { it in 'a'..'z' || it in 'A'..'Z' ||
                it in '0'..'9' || it == '-' || it == '_' || it == '.' || it == ':' }

    fun matchesConnect(serviceName: String, pairedGuid: String): Boolean {
        if (!isValidGuid(pairedGuid)) return false
        val prefix = "adb-" + pairedGuid
        return serviceName == prefix || serviceName.startsWith(prefix + "-")
    }
}
