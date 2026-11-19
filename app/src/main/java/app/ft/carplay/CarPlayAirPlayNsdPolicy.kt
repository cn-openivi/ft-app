package app.ft.carplay

import java.net.InetAddress
import java.util.Locale

/**
 * Strict source-only AirPlay DNS-SD result gate.
 * The _airplay._tcp service is public Bonjour protocol knowledge, never
 * proof that the peer is our Toyota or that FairPlay/MFi succeeded.
 */
internal object CarPlayAirPlayNsdPolicy {
    enum class State {
        WRONG_SERVICE, WRONG_NETWORK,
        INVALID_ENDPOINT, ENDPOINT_CANDIDATE_ONLY
    }
    class Result(
        val state: State,
        val address: InetAddress? = null,
        val port: Int? = null
    ) {
        override fun toString(): String = "CarPlayNsdResult($state, endpoint redacted)"
    }

    fun isAirPlayVideoService(serviceType: String?): Boolean {
        if (serviceType == null) return false
        val normalized = serviceType.lowercase(Locale.ROOT)
            .removeSuffix(".local.").removeSuffix(".local").trimEnd('.')
        return normalized == "_airplay._tcp"
    }

    fun resolve(
        serviceType: String?,
        sameVerifiedWifiNetwork: Boolean,
        port: Int,
        resolvedAddresses: List<InetAddress>
    ): Result {
        if (!isAirPlayVideoService(serviceType)) return Result(State.WRONG_SERVICE)
        if (!sameVerifiedWifiNetwork) return Result(State.WRONG_NETWORK)
        val selection = CarPlayAirPlayEndpointPolicy.select(
            srvPresent = true, srvPort = port,
            addressesFromSrvTarget = resolvedAddresses,
            networkBound = true
        )
        if (selection.status !=
            CarPlayAirPlayEndpointPolicy.Status.ENDPOINT_CANDIDATE_ONLY)
            return Result(State.INVALID_ENDPOINT)
        return Result(State.ENDPOINT_CANDIDATE_ONLY, selection.address, port)
    }
}
