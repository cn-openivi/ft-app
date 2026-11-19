package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayWifiCallbackGateTest {
    @Test fun capabilityAndExactNetworkNeededBeforeAirPlayDiscovery() {
        val q = CarPlayWifiCallbackGate<String>()
        assertTrue(q.begin(1))
        assertEquals(CarPlayWifiCallbackGate.Verdict.IGNORED,
            q.capabilities(1, "android-wifi-12", true))
        assertTrue(q.available(1, "android-wifi-12"))
        assertEquals(CarPlayWifiCallbackGate.Verdict.NETWORK_WIFI_CANDIDATE,
            q.capabilities(1, "android-wifi-12", true))
        assertEquals(CarPlayWifiCallbackGate.Verdict.IGNORED,
            q.capabilities(1, "android-wifi-12", true))
        assertFalse(q.available(1, "android-wifi-99"))
        assertFalse(q.lost(1, "android-wifi-99"))
        assertTrue(q.lost(1, "android-wifi-12"))
        assertEquals(CarPlayWifiCallbackGate.Verdict.IGNORED,
            q.capabilities(1, "android-wifi-12", true))
    }

    @Test fun wrongTransportNeverBecomesWifiEvenWithAvailableCallback() {
        val q = CarPlayWifiCallbackGate<String>()
        assertTrue(q.begin(5))
        assertTrue(q.available(5, "mobile"))
        assertEquals(CarPlayWifiCallbackGate.Verdict.WRONG_TRANSPORT,
            q.capabilities(5, "mobile", false))
        assertEquals(CarPlayWifiCallbackGate.Verdict.IGNORED,
            q.capabilities(5, "mobile", true))
        assertFalse(q.unavailable(5))
    }

    @Test fun oldNetworkEventsCannotCloseNewConnection() {
        val q = CarPlayWifiCallbackGate<Int>()
        q.begin(8)
        q.available(8, 100)
        assertTrue(q.begin(9))
        assertTrue(q.available(9, 200))
        assertFalse(q.lost(8, 100))
        assertFalse(q.unavailable(8))
        assertEquals(CarPlayWifiCallbackGate.Verdict.NETWORK_WIFI_CANDIDATE,
            q.capabilities(9, 200, true))
        assertFalse(q.begin(9))
        q.close()
        assertFalse(q.available(9, 200))
        assertTrue(q.begin(10))
        assertFalse(q.lost(9, 200))
    }
}
