package app.ft.supercast.adb

/**
 * AOSP Wi-Fi ADB advertises a GUID-linked connect service. Only the GUID
 * returned in SPAKE2-authenticated PeerInfo is allowed to select a port.
 * A changing mDNS suffix/port is a locator and never an authorization token.
 */
internal object AdbServiceIdentity {
    fun isValidGuid(guid: String): Boolean =
        guid.length in 12..128 && guid.startsWith("adb-") &&
            guid.all { it in 'a'..'z' || it in 'A'..'Z' ||
                it in '0'..'9' || it == '-' || it == '_' || it == '.' || it == ':' }

    fun matchesConnect(serviceName: String, pairedGuid: String): Boolean {
        if (!isValidGuid(pairedGuid)) return false
        // AOSP daemon/mdns.cpp registers ReadDeviceGuid() AS service name.
        // That GUID already contains the "adb-" prefix; never prepend it again.
        return serviceName == pairedGuid || serviceName.startsWith(pairedGuid + "-")
    }
}
