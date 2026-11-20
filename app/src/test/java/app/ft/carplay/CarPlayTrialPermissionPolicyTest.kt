package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayTrialPermissionPolicyTest {
    @Test fun android12AndLaterRequestOnlyBluetoothConnect() {
        assertEquals(CarPlayTrialPermissionPolicy.CONNECT,
            CarPlayTrialPermissionPolicy.runtimePermission(31))
        assertEquals(CarPlayTrialPermissionPolicy.CONNECT,
            CarPlayTrialPermissionPolicy.runtimePermission(36))
    }

    @Test fun android11AndEarlierUseManifestInstallTimeBluetooth() {
        assertNull(CarPlayTrialPermissionPolicy.runtimePermission(30))
        assertNull(CarPlayTrialPermissionPolicy.runtimePermission(29))
    }
}
