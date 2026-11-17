package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CarPlayPhoneIdentityTest {
    @Test fun realLocalAddressIsNormalizedWithoutGeneratingAnotherDevice() {
        val identity = CarPlayPhoneIdentity.fromRealLocalAddress("a4:22:33:44:55:66")
        assertNotNull(identity)
        assertEquals("A4:22:33:44:55:66", identity!!.bluetoothAddress)
    }

    @Test fun maskedOrMissingAndroidAddressCannotBeAdvertisedAsCarPlay() {
        for (invalid in listOf(
            null, "", "02:00:00:00:00:00", "00:00:00:00:00:00",
            "FF:FF:FF:FF:FF:FF", "01:22:33:44:55:66",
            "AA:BB:CC:DD:EE", "A4:22:33:44:55:GG"
        )) {
            assertNull("Invalid or masked adapter address must fail closed: $invalid",
                CarPlayPhoneIdentity.fromRealLocalAddress(invalid))
        }
    }

    @Test fun oemHfpAndCarPlayMustResolveToSameRealDevice() {
        val phone = CarPlayPhoneIdentity.fromRealLocalAddress("A4:22:33:44:55:66")!!
        assertEquals(CarPlayPhoneIdentity.HfpDeviceMatch.SAME_DEVICE,
            phone.compareHfpDevice("a4:22:33:44:55:66"))
        assertEquals(CarPlayPhoneIdentity.HfpDeviceMatch.DIFFERENT_DEVICE,
            phone.compareHfpDevice("B4:22:33:44:55:66"))
    }

    @Test fun noHfpObservationIsNotAFalsePass() {
        val phone = CarPlayPhoneIdentity.fromRealLocalAddress("A4:22:33:44:55:66")!!
        for (unavailable in listOf(null, "", "02:00:00:00:00:00", "broken")) {
            assertEquals(CarPlayPhoneIdentity.HfpDeviceMatch.NOT_OBSERVED,
                phone.compareHfpDevice(unavailable))
        }
    }
}
