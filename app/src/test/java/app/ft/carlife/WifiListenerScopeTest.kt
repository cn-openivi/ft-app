package app.ft.carlife

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class WifiListenerScopeTest {
    @Test fun explicitLoopbackListenerDoesNotBindToAllInterfaces() = runBlocking {
        val exact = InetAddress.getByName("127.0.0.1")
        val server = NetUtil.listen(0, bindAddress = exact) { true }!!
        try {
            assertEquals(exact, server.inetAddress)
            assertFalse(server.inetAddress.isAnyLocalAddress)
            Socket(exact, server.localPort).use { assertTrue(it.isConnected) }
        } finally {
            server.close()
        }
    }

    @Test fun legacyUnscopedApiRemainsForDeveloperLoopbackServer() = runBlocking {
        // Self/USB compatibility remains explicit and separate from the
        // production CarLifeService bindAddress provider.
        val server = NetUtil.listen(0) { true }!!
        try {
            assertTrue(server.inetAddress.isAnyLocalAddress)
        } finally {
            server.close()
        }
    }
}
