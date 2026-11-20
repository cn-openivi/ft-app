package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayLocationWireInspectorTest {
    private fun msg(body: String): Iap2ControlSessionCodec.Message {
        var cs = 0
        for (ch in body) cs = cs xor ch.code
        val value = ("$" + body + "*" + cs.toString(16).padStart(2,'0').uppercase() +
            "\r\n\u0000").toByteArray(Charsets.US_ASCII)
        return Iap2ControlSessionCodec.Message(0xFFFB,
            listOf(Iap2ControlSessionCodec.Parameter(0,value)))
    }

    @Test fun nmeaFamiliesClassifiedWithoutInventingSensorAxesOrUnits() {
        val kinds = mapOf(
            "GPGGA,121500,0,N,0,E" to CarPlayLocationWireInspector.Kind.GPS_GGA_RMC_OPAQUE,
            "PASCD,123,C,D,0,1,0.00,2.000" to CarPlayLocationWireInspector.Kind.SPEED_PASCD_OPAQUE,
            "PAGCD,unknown" to CarPlayLocationWireInspector.Kind.GYRO_PAGCD_OPAQUE,
            "PAACD,unknown" to CarPlayLocationWireInspector.Kind.ACCEL_PAACD_OPAQUE
        )
        for ((body, kind) in kinds)
            assertEquals(kind,CarPlayLocationWireInspector.inspect(msg(body)))
    }
    @Test fun corruptedCrcWrongParamAndMissingNulReject() {
        val good = msg("PAGCD,unknown")
        val corrupt = good.parameters.single().value.copyOf().also { it[6]='X'.code.toByte() }
        assertEquals(CarPlayLocationWireInspector.Kind.INVALID_WIRE,
            CarPlayLocationWireInspector.inspect(Iap2ControlSessionCodec.Message(0xFFFB,
                listOf(Iap2ControlSessionCodec.Parameter(0,corrupt)))))
        assertEquals(CarPlayLocationWireInspector.Kind.INVALID_WIRE,
            CarPlayLocationWireInspector.inspect(Iap2ControlSessionCodec.Message(0xFFFB,
                listOf(Iap2ControlSessionCodec.Parameter(4,good.parameters.single().value)))))
        assertEquals(CarPlayLocationWireInspector.Kind.INVALID_WIRE,
            CarPlayLocationWireInspector.inspect(Iap2ControlSessionCodec.Message(0xFFFB,
                listOf(Iap2ControlSessionCodec.Parameter(0,
                    good.parameters.single().value.dropLast(1).toByteArray())))))
    }
    @Test fun controlIsNotLocationAndNeverExposesRawPayload() {
        assertEquals(CarPlayLocationWireInspector.Kind.NOT_LOCATION_INFORMATION,
            CarPlayLocationWireInspector.inspect(
                Iap2ControlSessionCodec.Message(0x4301,emptyList())))
        assertFalse(CarPlayLocationWireInspector.inspect(
            msg("PASCD,PRIVATE_LOCATION_SAMPLE")).toString().contains("PRIVATE"))
    }
}
