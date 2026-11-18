package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Iap2IdentificationInfoInspectorTest {
    private val inspector = Iap2IdentificationInfoInspector

    @Test fun onlyTheActualAccessoryIdentificationMessageIsRecognized() {
        val other = Iap2ControlSessionCodec.Message(
            0x4301,
            listOf(Iap2ControlSessionCodec.Parameter(0, byteArrayOf(1)))
        )
        assertEquals(Iap2IdentificationInfoInspector.Result.NOT_IDENTIFICATION,
            inspector.inspect(other).result)
    }

    @Test fun emptyIdentificationIsNotPromotedToAccepted() {
        val msg = Iap2ControlSessionCodec.Message(0x1D01, emptyList())
        assertEquals(Iap2IdentificationInfoInspector.Result.NO_PARAMETERS,
            inspector.inspect(msg).result)
    }

    @Test fun knownParameterIdsAreOnlyAProofOfStructureNotOfAuthentication() {
        val rawSerial = "PRIVATE_SERIAL_123456".toByteArray()
        val msg = Iap2ControlSessionCodec.Message(
            0x1D01, listOf(
                Iap2ControlSessionCodec.Parameter(0x00, "Toyota".toByteArray()),
                Iap2ControlSessionCodec.Parameter(0x22, rawSerial)
            )
        )
        val got = inspector.inspect(msg)
        assertEquals(Iap2IdentificationInfoInspector.Result.STRUCTURE_OBSERVED, got.result)
        assertEquals(2, got.parameterCount)
        assertEquals(0, got.unknownParameterCount)
        assertFalse(got.toString().contains("PRIVATE_SERIAL_123456"))
        assertFalse(got.toString().contains("Toyota"))
    }

    @Test fun unknownParameterIdsStayVisibleAsCountWithoutRevealingValues() {
        val msg = Iap2ControlSessionCodec.Message(
            0x1D01, listOf(
                Iap2ControlSessionCodec.Parameter(0x00, byteArrayOf()),
                Iap2ControlSessionCodec.Parameter(0x33, "PRIVATE".toByteArray()),
                Iap2ControlSessionCodec.Parameter(0x45, byteArrayOf())
            )
        )
        val got = inspector.inspect(msg)
        assertEquals(Iap2IdentificationInfoInspector.Result.UNKNOWN_PARAMETER_IDS, got.result)
        assertEquals(3, got.parameterCount)
        assertEquals(2, got.unknownParameterCount)
        assertFalse(got.toString().contains("PRIVATE"))
    }
}
