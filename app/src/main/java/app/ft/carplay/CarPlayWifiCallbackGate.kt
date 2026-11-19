package app.ft.carplay

/**
 * FT200.2 one-session Android Wi-Fi network callback fence.
 * onAvailable only names a provisional Network. A matching subsequent
 * onCapabilitiesChanged with TRANSPORT_WIFI is required before advertising
 * it as an unverified network candidate to the AirPlay discovery stage.
 *
 * Session generations and actual Android Network identity guard against
 * late onLost, duplicate onAvailable and callbacks from another Network.
 * No SSID, PSK, BT MAC, device name or credentials are stored.
 */
internal class CarPlayWifiCallbackGate<NetworkToken : Any> {
    enum class Verdict { IGNORED, NETWORK_WIFI_CANDIDATE, WRONG_TRANSPORT }

    private var lastGeneration = 0L
    private var activeGeneration: Long? = null
    private var currentNetwork: NetworkToken? = null
    private var ready = false

    @Synchronized fun begin(generation: Long): Boolean {
        if (generation <= lastGeneration || generation <= 0L) return false
        lastGeneration = generation
        activeGeneration = generation
        currentNetwork = null
        ready = false
        return true
    }

    @Synchronized fun available(generation: Long, network: NetworkToken): Boolean {
        if (generation != activeGeneration) return false
        if (currentNetwork != null) return currentNetwork == network
        currentNetwork = network
        return true
    }

    @Synchronized
    fun capabilities(
        generation: Long,
        network: NetworkToken,
        observedWifiTransport: Boolean
    ): Verdict {
        if (generation != activeGeneration || currentNetwork != network)
            return Verdict.IGNORED
        if (!observedWifiTransport) {
            invalidate()
            return Verdict.WRONG_TRANSPORT
        }
        if (ready) return Verdict.IGNORED
        ready = true
        return Verdict.NETWORK_WIFI_CANDIDATE
    }

    @Synchronized fun lost(generation: Long, network: NetworkToken): Boolean {
        if (generation != activeGeneration || currentNetwork != network)
            return false
        invalidate()
        return true
    }

    @Synchronized fun unavailable(generation: Long): Boolean {
        if (generation != activeGeneration) return false
        invalidate()
        return true
    }

    @Synchronized fun close() { invalidate() }

    private fun invalidate() {
        activeGeneration = null
        currentNetwork = null
        ready = false
    }
}
