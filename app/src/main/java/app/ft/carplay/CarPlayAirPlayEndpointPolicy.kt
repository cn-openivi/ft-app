package app.ft.carplay

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Deterministic, source-only endpoint selection. mDNS discovery is not
 * pairing/authentication; all candidates must come from validated SRV target
 * A/AAAA records, have an actual port and still-active network provenance.
 */
internal object CarPlayAirPlayEndpointPolicy {
    enum class Status {
        NO_SRV, NO_ADDRESS, INVALID_PORT, IPV6_LINKLOCAL_MISSING_SCOPE,
        ADDRESS_NOT_USABLE, ENDPOINT_CANDIDATE_ONLY
    }
    data class Selection(val status: Status, val address: InetAddress? = null) {
        override fun toString(): String = "AirPlayEndpointSelection(status=" + status + ", redacted)"
    }

    fun select(
        srvPresent: Boolean, srvPort: Int?,
        addressesFromSrvTarget: List<InetAddress>,
        networkBound: Boolean
    ): Selection {
        if (!srvPresent || !networkBound) return Selection(Status.NO_SRV)
        if (srvPort == null || srvPort !in 1..65535)
            return Selection(Status.INVALID_PORT)
        if (addressesFromSrvTarget.isEmpty()) return Selection(Status.NO_ADDRESS)
        val valid = addressesFromSrvTarget.filter {
            !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress
        }
        if (valid.isEmpty()) return Selection(Status.ADDRESS_NOT_USABLE)
        val scoped = valid.filter {
            it !is Inet6Address || !it.isLinkLocalAddress || it.scopeId > 0
        }
        if (scoped.isEmpty()) return Selection(Status.IPV6_LINKLOCAL_MISSING_SCOPE)
        // Local preference only; not a claim about the target's Apple stack.
        val preferred = scoped.sortedWith(
            compareBy<InetAddress> { if (it.address.size == 4) 0 else 1 }
                .thenBy { it.hostAddress }
        ).first()
        return Selection(Status.ENDPOINT_CANDIDATE_ONLY, preferred)
    }
}
