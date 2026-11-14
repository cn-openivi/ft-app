package app.ft.iccoa

import app.ft.core.BluetoothCallsState
import app.ft.core.BluetoothCallsStatus
import app.ft.core.BluetoothMediaState
import app.ft.core.BluetoothProfilePeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IccoaCarBluetoothRolesTest {
    private val receiverId = "a".repeat(64)
    private val car = BluetoothProfilePeer("AA:BB:CC:DD:EE:FF", "Toyota")
    private val other = BluetoothProfilePeer("11:22:33:44:55:66", "Headset")
    private val hfp = BluetoothCallsState(BluetoothCallsStatus.CONNECTED, listOf("Toyota"), listOf(car))
    private val media = BluetoothMediaState(BluetoothCallsStatus.CONNECTED, listOf(car))

    @Test fun bothRolesRequireExactLiveProfileAddresses() {
        val roles = IccoaCarBluetoothRoleResolver.resolve(receiverId, "aa:bb:cc:dd:ee:ff", hfp, media)
        assertEquals(IccoaCarBluetoothMatch.HFP_AND_MEDIA, roles.match)
        assertTrue(roles.callsToCar)
        assertTrue(roles.mediaToCar)
        assertFalse(roles.otherCallsDevice)
        assertFalse(roles.otherMediaDevice)
    }

    @Test fun authenticatedProjectionIsNotAutomaticProofOfClassicBluetooth() {
        val roles = IccoaCarBluetoothRoleResolver.resolve(null, car.address, hfp, media)
        assertEquals(IccoaCarBluetoothMatch.PROJECTION_UNVERIFIED, roles.match)
        assertFalse(roles.callsToCar)
        assertFalse(roles.mediaToCar)
        val invalidReceiver = IccoaCarBluetoothRoleResolver.resolve("A".repeat(64), car.address, hfp, media)
        assertEquals(IccoaCarBluetoothMatch.PROJECTION_UNVERIFIED, invalidReceiver.match)
    }

    @Test fun randomMaskedAndMissingClassicCarAddressNeverMatch() {
        for (unknown in listOf(null, "", "02:00:00:00:00:00", "00:00:00:00:00:00", "AABBCC")) {
            val roles = IccoaCarBluetoothRoleResolver.resolve(receiverId, unknown, hfp, media)
            assertEquals(IccoaCarBluetoothMatch.CAR_ADDRESS_UNKNOWN, roles.match)
            assertFalse(roles.callsToCar)
            assertFalse(roles.mediaToCar)
        }
    }

    @Test fun unrelatedHeadsetCannotBeMistakenForHfpOfProjectedCar() {
        val headsetCalls = BluetoothCallsState(BluetoothCallsStatus.CONNECTED, listOf("Headset"), listOf(other))
        val roles = IccoaCarBluetoothRoleResolver.resolve(receiverId, car.address, headsetCalls, media)
        assertEquals(IccoaCarBluetoothMatch.MEDIA_ONLY, roles.match)
        assertFalse(roles.callsToCar)
        assertTrue(roles.mediaToCar)
        assertTrue(roles.otherCallsDevice)
        assertFalse(roles.otherMediaDevice)
    }

    @Test fun eachProfileCanReconnectWithoutChangingOtherRole() {
        val disconnectedMedia = BluetoothMediaState(BluetoothCallsStatus.DISCONNECTED)
        val first = IccoaCarBluetoothRoleResolver.resolve(receiverId, car.address, hfp, disconnectedMedia)
        assertEquals(IccoaCarBluetoothMatch.HFP_ONLY, first.match)
        assertTrue(first.callsToCar)
        assertFalse(first.mediaToCar)
        val second = IccoaCarBluetoothRoleResolver.resolve(receiverId, car.address, hfp, media)
        assertEquals(IccoaCarBluetoothMatch.HFP_AND_MEDIA, second.match)
    }

    @Test fun unknownHfpProfileDoesNotConvertToDisconnectedOrConnected() {
        val checking = BluetoothCallsState(BluetoothCallsStatus.CHECKING)
        val roles = IccoaCarBluetoothRoleResolver.resolve(receiverId, car.address, checking, media)
        assertEquals(IccoaCarBluetoothMatch.MEDIA_ONLY, roles.match)
        assertFalse(roles.callsToCar)
    }

    @Test fun connectedOtherMediaDeviceRemainsSeparate() {
        val headsetMedia = BluetoothMediaState(BluetoothCallsStatus.CONNECTED, listOf(other))
        val roles = IccoaCarBluetoothRoleResolver.resolve(receiverId, car.address, hfp, headsetMedia)
        assertEquals(IccoaCarBluetoothMatch.HFP_ONLY, roles.match)
        assertTrue(roles.otherMediaDevice)
    }
}
