package app.ft.iccoa

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The media listener/RTP route is owned by one authenticated ICCOA Wi-Fi Network.
 * The local address is the exact IPv4 obtained from that Network's LinkProperties.
 * It must never resolve to wildcard, cellular or another interface.
 */
internal class IccoaMediaNetworkScope(
    localIpv4: String,
    peerIpv4: String,
    private val networkCurrent: () -> Boolean
) {
    val local: Inet4Address = ipv4(localIpv4, "local")
    val peer: Inet4Address = ipv4(peerIpv4, "peer")

    fun isCurrent(): Boolean = networkCurrent()

    fun listener(port: Int): InetSocketAddress {
        require(port in 1..65535) { "Invalid ICCOA listen port" }
        return InetSocketAddress(local, port)
    }

    fun accepts(address: InetAddress): Boolean = isCurrent() && address == peer

    private fun ipv4(value: String, role: String): Inet4Address {
        // The input is already obtained from a Network's IPv4 LinkProperties/routes.
        // Never resolve a hostname or silently bind all addresses.
        require(value.matches(Regex("""(?:[0-9]{1,3}\.){3}[0-9]{1,3}"""))) {
            "ICCOA $role must be an IPv4 literal"
        }
        val parsed = InetAddress.getByName(value)
        require(parsed is Inet4Address && !parsed.isAnyLocalAddress &&
            !parsed.isMulticastAddress) { "Invalid ICCOA $role IPv4" }
        return parsed
    }
}
