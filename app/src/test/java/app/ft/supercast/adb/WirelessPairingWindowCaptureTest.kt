package app.ft.supercast.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessPairingWindowCaptureTest {
    @Test fun onlyArmedResolvedSettingsWithFreshMdnsPortCanSupplyCode() {
        var now = 100L
        val reader = WirelessPairingWindowCapture { now }
        assertNull(reader.codeForOpenedSystemDialog("com.android.settings", listOf("123456"), 40123))
        reader.arm("com.android.settings")
        assertNull(reader.codeForOpenedSystemDialog("other.app", listOf("123456"), 40123))
        assertNull(reader.codeForOpenedSystemDialog("com.android.settings", listOf("123456"), null))
        assertNull(reader.codeForOpenedSystemDialog("com.android.settings", listOf("123456"), 0))
        assertEquals("123456", reader.codeForOpenedSystemDialog(
            "com.android.settings", listOf("Pair with code", "123 456", "192.168.0.2:40123"), 40123))
        assertNull(reader.codeForOpenedSystemDialog("com.android.settings", listOf("123456"), 40123))
        reader.arm("com.android.settings", durationMs = 1000)
        now += 1001
        assertNull(reader.codeForOpenedSystemDialog("com.android.settings", listOf("123456"), 40123))
    }
}
