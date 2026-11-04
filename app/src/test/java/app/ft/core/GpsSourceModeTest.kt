package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class GpsSourceModeTest {
    @Test fun newIccoaInstallDefaultsToCarAndBaiduToPhone() {
        assertEquals(GpsSourceMode.CAR, GpsSourceMode.defaultFor(CarProtocol.ICCOA))
        assertEquals(GpsSourceMode.PHONE, GpsSourceMode.defaultFor(CarProtocol.CARLIFE))
    }

    @Test fun chosenGpsOriginNeverCrossUsesOtherOrigin() {
        assertTrue(GpsSourceMode.CAR.acceptsGnssFrom(GpsSourceMode.CAR))
        assertTrue(GpsSourceMode.PHONE.acceptsGnssFrom(GpsSourceMode.PHONE))
        assertFalse(GpsSourceMode.CAR.acceptsGnssFrom(GpsSourceMode.PHONE))
        assertFalse(GpsSourceMode.PHONE.acceptsGnssFrom(GpsSourceMode.CAR))
    }

    @Test fun phoneModeDoesNotAdvertiseOemLocationToAndroidAuto() {
        assertTrue(GpsSourceMode.CAR.advertisesCarLocation(CarProtocol.ICCOA))
        assertFalse(GpsSourceMode.PHONE.advertisesCarLocation(CarProtocol.ICCOA))
        assertFalse(GpsSourceMode.CAR.advertisesCarLocation(CarProtocol.CARLIFE))
        assertTrue(GpsSourceMode.PHONE.permitsVerifiedCarOdometry())
        assertTrue(GpsSourceMode.CAR.permitsVerifiedCarOdometry())
    }

    @Test fun savedStableIdsRoundTrip() {
        assertEquals(GpsSourceMode.CAR, GpsSourceMode.fromId("car"))
        assertEquals(GpsSourceMode.PHONE, GpsSourceMode.fromId("phone"))
    }
}
