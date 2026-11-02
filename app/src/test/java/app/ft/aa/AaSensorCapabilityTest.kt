package app.ft.aa

import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test

/** Sensor requests must match FT's advertised and implemented capabilities. */
class AaSensorCapabilityTest {
    @Test fun iccoaNormalModeOnlyOffersLocationDrivingAndNight() {
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_LOCATION, true, false))
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_NIGHT_MODE, true, false))
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_DRIVING_STATUS, true, false))
        assertFalse(AaProtocol.supportsSensor(AaProtocol.SENSOR_GEAR, true, false))
    }

    @Test fun gearOptInDoesNotAdvertiseUnsupportedCarSpeedOrBrake() {
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_GEAR, true, true))
        assertFalse(AaProtocol.supportsSensor(3, true, true)) // speed units unverified
        assertFalse(AaProtocol.supportsSensor(7, true, true)) // brake not available
        assertFalse(AaProtocol.supportsSensor(0, true, true))
        assertFalse(AaProtocol.supportsSensor(26, true, true))
        assertFalse(AaProtocol.supportsSensor(999, true, true))
    }

    @Test fun otherProjectionProtocolsCannotPretendToProvideIccoaSensors() {
        assertFalse(AaProtocol.supportsSensor(AaProtocol.SENSOR_LOCATION, false, true))
        assertFalse(AaProtocol.supportsSensor(AaProtocol.SENSOR_GEAR, false, true))
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_DRIVING_STATUS, false, false))
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_NIGHT_MODE, false, false))
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
