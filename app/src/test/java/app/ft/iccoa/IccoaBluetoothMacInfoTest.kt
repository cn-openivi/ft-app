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


    @Test fun autoAssociationIsReceiverBoundAndRequiresVerifiedOemSupport() {
        val trusted = IccoaBluetoothMacInfo.hashHuObservedPhoneMac("AA:BB:CC:DD:EE:FF")!!
        val other = IccoaBluetoothMacInfo.hashHuObservedPhoneMac("11:22:33:44:55:66")!!
        // Even a known hash must not be sent before crypto authentication.
        assertNull(IccoaBluetoothMacInfo.chooseAutomaticHash(false, true, trusted, other, "AA:BB:CC:DD:EE:FF"))
        // Unknown manufacturer/receiver cannot inherit Toyota method22 just from a MAC.
        assertNull(IccoaBluetoothMacInfo.chooseAutomaticHash(true, false, null, null, "AA:BB:CC:DD:EE:FF"))
        assertNull(IccoaBluetoothMacInfo.chooseAutomaticHash(true, false, null, trusted, null))
        // Previously verified receiver-specific hash can be reused without any UI toggle.
        assertEquals(trusted, IccoaBluetoothMacInfo.chooseAutomaticHash(true, false, trusted, other, "11:22:33:44:55:66"))
        // The exact primary Toyota MAC can be learned only when the OS actually exposes it.
        assertEquals(trusted, IccoaBluetoothMacInfo.chooseAutomaticHash(true, true, null, null, "AA:BB:CC:DD:EE:FF"))
        assertEquals(trusted, IccoaBluetoothMacInfo.chooseAutomaticHash(true, true, null, trusted, "11:22:33:44:55:66"))
        // Masked/placeholder and guessed identity do not emit CONTROL22.
        assertNull(IccoaBluetoothMacInfo.chooseAutomaticHash(true, true, null, null, "02:00:00:00:00:00"))
        assertNull(IccoaBluetoothMacInfo.chooseAutomaticHash(true, true, null, null, null))
        // SHA digest of the wrong format must not be trusted as a stored phone identity.
        assertNull(IccoaBluetoothMacInfo.chooseAutomaticHash(true, false, "AB".repeat(32), null, null))
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
