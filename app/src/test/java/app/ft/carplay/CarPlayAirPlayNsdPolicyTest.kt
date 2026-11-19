package app.ft.carplay

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class CarPlayAirPlayNsdPolicyTest {
    private val p = CarPlayAirPlayNsdPolicy
    private val peer = InetAddress.getByName("192.0.2.99")

    @Test fun onlyActualVideoAirPlayServiceTypeIsCandidate() {
        assertTrue(p.isAirPlayVideoService("_airplay._tcp."))
        assertTrue(p.isAirPlayVideoService("_airplay._tcp.local."))
        assertFalse(p.isAirPlayVideoService("_raop._tcp."))
        assertFalse(p.isAirPlayVideoService("_http._tcp."))
        assertEquals(CarPlayAirPlayNsdPolicy.State.WRONG_SERVICE,
            p.resolve("_raop._tcp.", true, 7100, listOf(peer)).state)
    }

    @Test fun discoveredServiceFromWrongAndroidNetworkIsRejected() {
        assertEquals(CarPlayAirPlayNsdPolicy.State.WRONG_NETWORK,
            p.resolve("_airplay._tcp.", false, 7100, listOf(peer)).state)
        assertEquals(CarPlayAirPlayNsdPolicy.State.INVALID_ENDPOINT,
            p.resolve("_airplay._tcp.", true, 0, listOf(peer)).state)
        assertEquals(CarPlayAirPlayNsdPolicy.State.INVALID_ENDPOINT,
            p.resolve("_airplay._tcp.", true, 7100,
                listOf(InetAddress.getByName("127.0.0.1"))).state)
    }

    @Test fun validDiscoveredEndpointIsStillOnlyUnverifiedCandidate() {
        val result = p.resolve("_airplay._tcp.", true, 7163, listOf(peer))
        assertEquals(CarPlayAirPlayNsdPolicy.State.ENDPOINT_CANDIDATE_ONLY,
            result.state)
        assertEquals(7163, result.port)
        assertEquals(peer, result.address)
        assertFalse(result.toString().contains("192.0.2.99"))
        assertFalse(result.toString().contains("7163"))
    }
}
