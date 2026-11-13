package app.ft.core

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionPermissionsTest {
    @Test fun corePermissionsExcludeOptionalAudioAndNotifications() {
        for (sdk in listOf(29, 32, 33, 36)) {
            val required = ConnectionPermissions.requiredFor(sdk)
            assertFalse(required.contains(Manifest.permission.RECORD_AUDIO))
            assertFalse(required.contains(Manifest.permission.POST_NOTIFICATIONS))
            assertFalse(required.contains(Manifest.permission.BLUETOOTH_ADVERTISE))
        }
    }

    @Test fun android12UsesBluetoothAndLocation() {
        assertEquals(listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        ), ConnectionPermissions.requiredFor(32))
    }

    @Test fun android13UsesNearbyWifiInsteadOfLegacyLocation() {
        assertEquals(listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ), ConnectionPermissions.requiredFor(33))
    }
}
