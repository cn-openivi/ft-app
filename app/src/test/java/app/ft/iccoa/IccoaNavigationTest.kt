package app.ft.iccoa

import app.ft.core.NavigationInfo
import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test

class IccoaNavigationTest {
    @Test fun activeNavigationTellsCarLinkSensorManagerToStart() {
        val p = ProtoReader(IccoaNavigation.encode(NavigationInfo(
            active = true, road = "Tverskaya", distanceMeters = 850)))
        assertTrue(p.bool(1))
        assertEquals("850", p.string(3))
        assertEquals("m", p.string(4))
        assertEquals("Tverskaya", p.string(6))
        assertEquals(11, IccoaNavigation.CONTROL_METHOD)
    }
    @Test fun aaProjectionAsksForSensorWithoutClaimingNavigationRoute() {
        val inactive = NavigationInfo()
        val request = IccoaNavigation.sensorActivation(inactive, aaConnected = true)
        assertTrue(ProtoReader(IccoaNavigation.encode(request)).bool(1))
        assertEquals("", request.road)
        assertNull(request.distanceMeters)
        assertFalse(IccoaNavigation.sensorActivation(inactive, aaConnected = false).active)
    }

    @Test fun ftHomeProjectionActivatesGpsWithoutAndroidAuto() {
        val stopped = NavigationInfo()
        assertTrue(IccoaNavigation.sensorActivation(stopped,
            aaConnected = false, ftProjectionActive = true).active)
        assertFalse(IccoaNavigation.sensorActivation(stopped,
            aaConnected = false, ftProjectionActive = false).active)
    }

    @Test fun actualGuidedRouteRemainsActiveEvenWhenAaVisibilityChanges() {
        val guided = NavigationInfo(active = true, road = "Route", distanceMeters = 850)
        assertEquals(guided, IccoaNavigation.sensorActivation(guided, aaConnected = true))
        assertEquals(guided, IccoaNavigation.sensorActivation(guided, aaConnected = false))
    }

    @Test fun guidedManeuverUsesExactOemFieldsAndRealImageBytes() {
        val icon = byteArrayOf(0x01, 0x02, 0x03)
        val p = ProtoReader(IccoaNavigation.encode(NavigationInfo(
            active = true, maneuver = 8, road = "Lenina", distanceMeters = 300), icon))
        assertTrue(p.bool(1))
        assertArrayEquals(icon, p.bytes(2))
        assertEquals("300", p.string(3))
        assertEquals("m", p.string(4))
        assertEquals("Turn left", p.string(5))
        assertEquals("Lenina", p.string(6))
        assertEquals("Turn left", p.string(7))
        assertEquals("Lenina", p.string(8))
        assertFalse(p.has(9))
    }

    @Test fun gpsOnlyActivationCannotInventTurnOrDirectionIcon() {
        val activation = IccoaNavigation.sensorActivation(NavigationInfo(),
            aaConnected = true)
        val p = ProtoReader(IccoaNavigation.encode(activation, byteArrayOf(1, 2)))
        assertTrue(p.bool(1))
        assertFalse(p.has(2))
        assertFalse(p.has(5))
        assertFalse(p.has(7))
        assertFalse(p.has(8))
    }

    @Test fun incompleteTurnDoesNotAdvertiseTurnIcon() {
        val p = ProtoReader(IccoaNavigation.encode(NavigationInfo(
            active = true, maneuver = 8, distanceMeters = null), byteArrayOf(1)))
        assertFalse(p.has(2))
        assertFalse(p.has(5))
    }

    @Test fun inactiveNavigationTellsCarLinkSensorManagerToStop() {
        val p = ProtoReader(IccoaNavigation.encode(NavigationInfo(active = false)))
        assertFalse(p.bool(1))
    }
}
