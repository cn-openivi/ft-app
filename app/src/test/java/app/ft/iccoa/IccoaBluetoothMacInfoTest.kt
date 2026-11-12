package app.ft.iccoa

import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test

/** XA60 OEM ucar.proto ControlIndex.bluetooth_mac=22. Source-only FT180 staging. */
class IccoaBluetoothMacInfoTest {
    @Test fun exactOemSha256And66ByteAddMessage() {
        val digest = IccoaBluetoothMacInfo.hashHuObservedPhoneMac("AA:BB:CC:DD:EE:FF")
        assertEquals("261900fb1113aa4db748173e13abd88ae0faf7536891ee956fea94d58673cbdb", digest)
        val wire = IccoaBluetoothMacInfo.addHash(digest!!)
        assertNotNull(wire)
        assertEquals(22, IccoaBluetoothMacInfo.CONTROL_METHOD)
        assertEquals(66, wire!!.size)
        assertEquals(0x12, wire[0].toInt() and 0xff)
        assertEquals(0x40, wire[1].toInt() and 0xff)
        val parsed = ProtoReader(wire)
        assertEquals(setOf(2), parsed.fields.keys) // ADD=0 is default/omitted
        assertEquals(digest, parsed.string(2))
    }

    @Test fun huAddressCaseMustNotBeSilentlyNormalized() {
        val upper = IccoaBluetoothMacInfo.hashHuObservedPhoneMac("AA:BB:CC:DD:EE:FF")
        val lower = IccoaBluetoothMacInfo.hashHuObservedPhoneMac("aa:bb:cc:dd:ee:ff")
        assertNotEquals(upper, lower)
    }

    @Test fun rejectMaskedBroadcastEmptyOrMalformedInputs() {
        for (invalid in listOf("", "02:00:00:00:00:00", "00:00:00:00:00:00",
            "FF:FF:FF:FF:FF:FF", "XX:BB:CC:DD:EE:FF",
            "AA:BB:CC:DD:EE", " AA:BB:CC:DD:EE:FF")) {
            assertNull("Unexpectedly accepted: $invalid", IccoaBluetoothMacInfo.hashHuObservedPhoneMac(invalid))
        }
        assertNull(IccoaBluetoothMacInfo.addHash(""))
        assertNull(IccoaBluetoothMacInfo.addHash("F".repeat(64)))
    }
}
