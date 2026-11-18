package app.ft.carplay

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class CarPlayAirPlayEndpointPolicyTest {
    private fun v4(s: String): InetAddress = InetAddress.getByName(s)

    @Test fun noControlEndpointWithoutSrvAndRealPort() {
        val candidate = listOf(v4("192.0.2.42"))
        val p = CarPlayAirPlayEndpointPolicy
        assertEquals(CarPlayAirPlayEndpointPolicy.Status.NO_SRV,
            p.select(false,7000,candidate,true).status)
        assertEquals(CarPlayAirPlayEndpointPolicy.Status.NO_SRV,
            p.select(true,7000,candidate,false).status)
        assertEquals(CarPlayAirPlayEndpointPolicy.Status.INVALID_PORT,
            p.select(true,null,candidate,true).status)
        assertEquals(CarPlayAirPlayEndpointPolicy.Status.INVALID_PORT,
            p.select(true,0,candidate,true).status)
        assertEquals(CarPlayAirPlayEndpointPolicy.Status.NO_ADDRESS,
            p.select(true,7100,emptyList(),true).status)
    }

    @Test fun noDefaultPortAndNoLoopbackPeer() {
        val p = CarPlayAirPlayEndpointPolicy
        assertEquals(CarPlayAirPlayEndpointPolicy.Status.ADDRESS_NOT_USABLE,
            p.select(true,7100,listOf(v4("127.0.0.1")),true).status)
        assertEquals(CarPlayAirPlayEndpointPolicy.Status.ENDPOINT_CANDIDATE_ONLY,
            p.select(true,7163,listOf(v4("192.0.2.42")),true).status)
    }

    @Test fun addressSelectionPrefersRealV4AndRedactsHostFromDebug() {
        val p = CarPlayAirPlayEndpointPolicy
        val result = p.select(true,7163,listOf(v4("2001:db8::a"),
            v4("192.0.2.42")),true)
        assertEquals("192.0.2.42",result.address!!.hostAddress)
        assertFalse(result.toString().contains("192.0.2.42"))
    }

    @Test fun unscopedIpv6LinkLocalCannotSilentlyLeaveDefaultInterface() {
        val p = CarPlayAirPlayEndpointPolicy
        val result = p.select(true,7100,listOf(v4("fe80::1234")),true)
        assertEquals(CarPlayAirPlayEndpointPolicy.Status.IPV6_LINKLOCAL_MISSING_SCOPE,
            result.status)
    }
}
