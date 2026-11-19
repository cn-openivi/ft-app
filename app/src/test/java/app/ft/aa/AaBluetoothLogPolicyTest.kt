package app.ft.aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AaBluetoothLogPolicyTest {
    @Test fun neverAllowWifiPasswordInRawPacketDiagnostics() {
        assertFalse(AaBluetoothLogPolicy.mayLogPayload(AaProtocol.BT_WIFI_INFO_RESPONSE))
    }

    @Test fun allOtherKnownBluetoothControlPacketsKeepDiagnostics() {
        assertTrue(AaBluetoothLogPolicy.mayLogPayload(AaProtocol.BT_WIFI_INFO_REQUEST))
        assertTrue(AaBluetoothLogPolicy.mayLogPayload(AaProtocol.BT_WIFI_START_REQUEST))
        assertTrue(AaBluetoothLogPolicy.mayLogPayload(AaProtocol.BT_WIFI_VERSION_REQUEST))
        assertTrue(AaBluetoothLogPolicy.mayLogPayload(AaProtocol.BT_WIFI_VERSION_RESPONSE))
        assertTrue(AaBluetoothLogPolicy.mayLogPayload(AaProtocol.BT_WIFI_CONNECT_STATUS))
    }
}
