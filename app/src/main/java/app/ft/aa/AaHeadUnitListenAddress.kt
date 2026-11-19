package app.ft.aa

import java.net.InetAddress

/**
 * Wireless AA accepts only connections to the verified LOHS interface.
 * Developer/self mode is strictly local loopback, not a public wildcard
 * listener on every Wi-Fi, VPN and cellular interface.
 */
internal object AaHeadUnitListenAddress {
    fun selected(wireless: Boolean, hotspotIpv4: String?): InetAddress? {
        if (!wireless) return InetAddress.getByName("127.0.0.1")
        val verified = hotspotIpv4?.let { AaHotspotAddressPolicy.select(listOf(it)) }
            ?: return null
        return InetAddress.getByName(verified)
    }
}
