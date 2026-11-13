package app.ft.supercast.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingStateTest {
    @Test fun sourcesAreDistinctAndContainNoUserConfigurablePort() {
        assertEquals("_adb-tls-pairing._tcp", WirelessAdbDiscovery.PAIRING)
        assertEquals("_adb-tls-connect._tcp", WirelessAdbDiscovery.CONNECT)
    }

    @Test fun pairingCodeRequiresSixDigits() {
        assertEquals(true, WirelessPairingCode.isValid("123456"))
        assertEquals(false, WirelessPairingCode.isValid("12 3456"))
        assertEquals(false, WirelessPairingCode.isValid("12345"))
        assertEquals(false, WirelessPairingCode.isValid("12345a"))
        assertEquals(false, WirelessPairingCode.isValid("1234567"))
    }
}

