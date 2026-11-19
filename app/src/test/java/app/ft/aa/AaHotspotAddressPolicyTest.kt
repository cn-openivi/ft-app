package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaHotspotAddressPolicyTest {
    @Test fun wirelessWpa2NeverAdvertisesBlankOrUnsupportedPassphrase() {
        assertFalse(AaHotspotAddressPolicy.credentialsUsable("", "12345678"))
        assertFalse(AaHotspotAddressPolicy.credentialsUsable("MyHotspot", ""))
        assertFalse(AaHotspotAddressPolicy.credentialsUsable("MyHotspot", "1234567"))
        assertFalse(AaHotspotAddressPolicy.credentialsUsable("MyHotspot", "a".repeat(64)))
        assertTrue(AaHotspotAddressPolicy.credentialsUsable("MyHotspot", "12345678"))
        assertTrue(AaHotspotAddressPolicy.credentialsUsable("MyHotspot", "a".repeat(63)))
    }

    @Test fun useUniqueRealPrivateApAddressOnly() {
        assertEquals("192.168.55.1", AaHotspotAddressPolicy.select(listOf("192.168.55.1")))
        assertEquals("172.20.10.1", AaHotspotAddressPolicy.select(listOf("172.20.10.1")))
        assertEquals("10.42.0.1", AaHotspotAddressPolicy.select(listOf("10.42.0.1")))
    }

    @Test fun noAddressBeforeCallbackMeansNoBluetoothWifiStart() {
        assertNull(AaHotspotAddressPolicy.select(emptyList()))
    }

    @Test fun neverPickArbitraryIpBetweenTwoApCandidates() {
        assertNull(AaHotspotAddressPolicy.select(listOf("192.168.43.1", "192.168.145.1")))
    }

    @Test fun ignoresLoopbackPublicMulticastAndWildcardIps() {
        assertNull(AaHotspotAddressPolicy.select(listOf("127.0.0.1", "0.0.0.0", "8.8.8.8", "224.0.0.1")))
    }

    @Test fun repeatedSameInterfaceIpIsNotAmbiguous() {
        assertEquals("192.168.54.1", AaHotspotAddressPolicy.select(listOf("192.168.54.1", "192.168.54.1")))
    }
}
