package app.ft.aa

import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test

/** Sensor requests must match FT's advertised and implemented capabilities. */
class AaSensorCapabilityTest {
    @Test fun iccoaAlwaysSupportsRealGearAndLocationAlongWithDrivingAndNight() {
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_LOCATION, true))
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_GEAR, true))
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_NIGHT_MODE, true))
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_DRIVING_STATUS, true))
    }

    @Test fun automaticGearDoesNotAdvertiseUnsupportedCarSpeedOrBrake() {
        assertFalse(AaProtocol.supportsSensor(3, true)) // OEM speed units unverified
        assertFalse(AaProtocol.supportsSensor(7, true)) // brake not available
        assertFalse(AaProtocol.supportsSensor(0, true))
        assertFalse(AaProtocol.supportsSensor(26, true))
        assertFalse(AaProtocol.supportsSensor(999, true))
    }

    @Test fun otherProjectionProtocolsCannotPretendToProvideIccoaSensors() {
        assertFalse(AaProtocol.supportsSensor(AaProtocol.SENSOR_LOCATION, false))
        assertFalse(AaProtocol.supportsSensor(AaProtocol.SENSOR_GEAR, false))
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_DRIVING_STATUS, false))
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_NIGHT_MODE, false))
    }

    @Test fun iccoaGearRemainsSupportedWhenGnssLocationIsDisabled() {
        // The SDP still lists GEAR=8 when a separate OEM LOCATION gate is
        // disabled. The earlier runtime predicate incorrectly rejected GEAR
        // because it used the location policy as the isIccoa argument.
        val sensorAdvertisement = AaMessages.serviceDiscoveryResponse(
            1920, 1008, 30, 240, false, "XA60",
            advertiseCarLocation = false, advertiseCarGear = true
        )
        val sensor = ProtoReader(sensorAdvertisement).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }.message(2)!!
        assertEquals(listOf(
            AaProtocol.SENSOR_GEAR,
            AaProtocol.SENSOR_DRIVING_STATUS,
            AaProtocol.SENSOR_NIGHT_MODE
        ), sensor.messages(1).map { it.int(1) })
        assertTrue(AaProtocol.supportsSensor(
            AaProtocol.SENSOR_GEAR, isIccoa = true, carLocationOffered = false
        ))
        assertFalse(AaProtocol.supportsSensor(
            AaProtocol.SENSOR_LOCATION, isIccoa = true, carLocationOffered = false
        ))
        assertFalse(AaProtocol.supportsSensor(
            AaProtocol.SENSOR_GEAR, isIccoa = false, carLocationOffered = false
        ))
        assertFalse(AaProtocol.supportsSensor(
            AaProtocol.SENSOR_LOCATION, isIccoa = false, carLocationOffered = true
        ))
    }

    @Test fun sensorStartResponseEncodesValidAndInvalidStatus() {
        val ok = ProtoReader(AaMessages.sensorStartResponse()).int(1)
        val rejected = ProtoReader(
            AaMessages.sensorStartResponse(AaProtocol.STATUS_INVALID_SENSOR)).int(1)
        assertEquals(0, ok)
        assertEquals(-9, rejected)
        assertArrayEquals(AaMessages.sensorStartResponse(AaProtocol.STATUS_OK),
            AaMessages.sensorStartResponse())
    }
}
