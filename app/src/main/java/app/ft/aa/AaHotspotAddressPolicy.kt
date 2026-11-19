package app.ft.aa

import java.net.Inet4Address
import java.net.InetAddress

/** A real local-only hotspot address is mandatory; arbitrary first Wi-Fi,
 * mobile/VPN IPv4 and platform-specific fallback gateways are not evidence.
 */
internal object AaHotspotAddressPolicy {
    /** FT advertises WPA2_PERSONAL, never OPEN: Android must provide a valid
     * SSID and WPA2 passphrase before sending any wireless AA credentials.
     * The passphrase is only tested in memory and must not enter diagnostics.
     */
    fun credentialsUsable(ssid: String, password: String): Boolean =
        ssid.isNotBlank() && password.length in 8..63
    fun select(candidates: List<String>): String? {
        val verified = candidates.mapNotNull { name ->
            val ip = runCatching { InetAddress.getByName(name) }.getOrNull()
            if (ip is Inet4Address && ip.isSiteLocalAddress && !ip.isAnyLocalAddress
                && !ip.isLoopbackAddress && !ip.isMulticastAddress) ip.hostAddress else null
        }.distinct()
        return verified.singleOrNull()
    }
}
