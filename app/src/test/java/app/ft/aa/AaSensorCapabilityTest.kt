package app.ft.aa

import app.ft.core.ProtoReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sensor status reply must match what FT actually advertises and provides. */
class AaSensorCapabilityTest {
    @Test fun advertisedAndImplementedSensorsAreAccepted() {
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_DRIVING_STATUS))
        assertTrue(AaProtocol.supportsSensor(AaProtocol.SENSOR_NIGHT_MODE))
    }

    @Test fun unavailableAndUnknownSensorsAreRejected() {
        for (type in listOf(0, 1, 3, 7, 8, 26, 999)) {
            assertFalse("type=$type", AaProtocol.supportsSensor(type))
        }
    }

    @Test fun sensorStatusIsEncodedInProtobuf() {
        assertEquals(AaProtocol.STATUS_OK,
            ProtoReader(AaMessages.sensorStartResponse()).int(1))
        assertEquals(AaProtocol.STATUS_INVALID_SENSOR,
            ProtoReader(AaMessages.sensorStartResponse(AaProtocol.STATUS_INVALID_SENSOR)).int(1))
    }
}
