package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class IccoaMediaNetworkScopeTest {
    @Test fun rtspAndUibcUseExactSelectedLocalIpv4AndUnchangedPorts() {
        val scope = IccoaMediaNetworkScope("127.0.0.1", "127.0.0.2") { true }
        val rtsp = scope.listener(7236)
        val uibc = scope.listener(4321)
        assertEquals(InetAddress.getByName("127.0.0.1"), rtsp.address)
        assertEquals(InetAddress.getByName("127.0.0.1"), uibc.address)
        assertEquals(7236, rtsp.port)
        assertEquals(4321, uibc.port)
        assertFalse(rtsp.address.isAnyLocalAddress)
        assertFalse(uibc.address.isAnyLocalAddress)
        // Java sockets can bind to the exact same loopback address; the
        // production port numbers are asserted above without stealing them.
        ServerSocket().use { it.bind(java.net.InetSocketAddress(scope.local, 0)) }
    }

    @Test fun peerValidationAllowsOnlyAuthenticatedOemAddress() {
        val scope = IccoaMediaNetworkScope("127.0.0.1", "127.0.0.2") { true }
        assertTrue(scope.accepts(InetAddress.getByName("127.0.0.2")))
        assertFalse(scope.accepts(InetAddress.getByName("127.0.0.3")))
        assertFalse(scope.accepts(InetAddress.getByName("127.0.0.1")))
    }

    @Test fun networkAtoBReplacementRejectsAllOldRtspRtpAndUibcWork() {
        val gate = IccoaNetworkCallbackGate<String>()
        gate.onAvailable("A")
        val first = IccoaMediaNetworkScope("127.0.0.1", "127.0.0.2") {
            gate.isCurrent("A")
        }
        assertTrue(first.isCurrent())
        assertTrue(first.accepts(InetAddress.getByName("127.0.0.2")))
        gate.onAvailable("B")
        val second = IccoaMediaNetworkScope("127.0.0.1", "127.0.0.2") {
            gate.isCurrent("B")
        }
        assertFalse(first.isCurrent())
        assertFalse(first.accepts(InetAddress.getByName("127.0.0.2")))
        assertTrue(second.isCurrent())
        assertTrue(second.accepts(InetAddress.getByName("127.0.0.2")))
        // A's delayed onLost must not release the current B lane.
        assertFalse(gate.onLost("A"))
        assertTrue(second.isCurrent())
        assertTrue(gate.onLost("B"))
        assertFalse(second.isCurrent())
    }

    @Test fun rejectsWildcardDnsNamesIpv6AndBadPortsInsteadOfFallingBack() {
        for (invalid in listOf("0.0.0.0", "::", "localhost", "8.8.8.8.example", "999.1.1.1")) {
            assertThrows(Exception::class.java) {
                IccoaMediaNetworkScope(invalid, "127.0.0.2") { true }
            }
        }
        val scope = IccoaMediaNetworkScope("127.0.0.1", "127.0.0.2") { true }
        assertThrows(IllegalArgumentException::class.java) { scope.listener(0) }
        assertThrows(IllegalArgumentException::class.java) { scope.listener(65536) }
    }

    @Test fun lostNetworkDisablesUseOfPreviouslyVerifiedPeer() {
        var live = true
        val scope = IccoaMediaNetworkScope("127.0.0.1", "127.0.0.2") { live }
        assertTrue(scope.accepts(InetAddress.getByName("127.0.0.2")))
        live = false
        assertFalse(scope.accepts(InetAddress.getByName("127.0.0.2")))
    }
}
