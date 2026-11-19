package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaHeadUnitListenAddressTest {
    @Test fun developerSelfModeBindsOnlyIpv4Loopback() {
        val ip = AaHeadUnitListenAddress.selected(false, null)!!
        assertEquals("127.0.0.1", ip.hostAddress)
        assertTrue(ip.isLoopbackAddress)
        assertFalse(ip.isAnyLocalAddress)
    }

    @Test fun developerModeNeverInheritsCellularOrVpnIpv4() {
        val ip = AaHeadUnitListenAddress.selected(false, "192.168.43.1")!!
        assertEquals("127.0.0.1", ip.hostAddress)
    }

    @Test fun wirelessModeBindsOnlyVerifiedHotspotIpv4() {
        val ip = AaHeadUnitListenAddress.selected(true, "192.168.53.1")!!
        assertEquals("192.168.53.1", ip.hostAddress)
        assertFalse(ip.isAnyLocalAddress)
        assertFalse(ip.isLoopbackAddress)
    }

    @Test fun wirelessModeDoesNotFallbackToWildcardWithoutHotspot() {
        assertNull(AaHeadUnitListenAddress.selected(true, null))
        assertNull(AaHeadUnitListenAddress.selected(true, "0.0.0.0"))
        assertNull(AaHeadUnitListenAddress.selected(true, "127.0.0.1"))
        assertNull(AaHeadUnitListenAddress.selected(true, "8.8.8.8"))
    }
}
