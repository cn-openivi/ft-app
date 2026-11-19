package app.ft.supercast.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbServiceIdentityTest {
    @Test fun onlyAuthenticatedDeviceGuidSelectsConnectService() {
        val guid = "adb-14141FDF600081-QXjCrW"
        assertTrue(AdbServiceIdentity.isValidGuid(guid))
        assertTrue(AdbServiceIdentity.matchesConnect("$guid", guid))
        assertTrue(AdbServiceIdentity.matchesConnect("$guid-rAnDoM", guid))
        assertFalse(AdbServiceIdentity.matchesConnect("adb-other-rAnDoM", guid))
        assertFalse(AdbServiceIdentity.matchesConnect("${guid}evil-rAnDoM", guid))
        assertFalse(AdbServiceIdentity.matchesConnect("studio-$guid", guid))
        assertFalse(AdbServiceIdentity.matchesConnect("adb-$guid-rAnDoM", "bad guid"))
    }

    @Test fun rejectMalformedGuidAndNamesFromOtherDevices() {
        assertFalse(AdbServiceIdentity.isValidGuid("123"))
        assertFalse(AdbServiceIdentity.isValidGuid("device name with spaces"))
        assertFalse(AdbServiceIdentity.isValidGuid("../unsafe/path"))
        assertFalse(AdbServiceIdentity.matchesConnect("adb-same", ""))
    }
}
