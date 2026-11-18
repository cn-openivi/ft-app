package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2CarPlayStartSchemaTest {
    private val schema = Iap2CarPlayStartSchema

    private fun tlv(id: Int, value: ByteArray): ByteArray {
        val length = value.size + 4
        return byteArrayOf(
            (length ushr 8).toByte(), length.toByte(),
            (id ushr 8).toByte(), id.toByte()
        ) + value
    }

    private fun cString(value: String) = value.toByteArray(Charsets.UTF_8) + byteArrayOf(0)

    private fun start(vararg parameters: Iap2ControlSessionCodec.Parameter) =
        Iap2ControlSessionCodec.Message(
            Iap2ControlSessionCodec.CARPLAY_START_SESSION,
            parameters.toList()
        )

    private fun parameter(id: Int, value: ByteArray) =
        Iap2ControlSessionCodec.Parameter(id, value)

    @Test fun twoLevelPathsAndCorrectWireWidthsAreRecognized() {
        val packet = start(
            parameter(0, tlv(0, cString("192.0.2.10"))),
            parameter(1, tlv(0, cString("Toyota")) +
                tlv(1, cString("SECRET_PASSWORD")) +
                tlv(2, byteArrayOf(6)) +
                tlv(3, cString("192.0.2.20")) +
                tlv(4, byteArrayOf(2))),
            parameter(2, byteArrayOf(0, 0, 0x1f, 0x90.toByte())),
            parameter(3, cString("PRIVATE_DEVICE_ID")),
            parameter(7, tlv(0, cString("private-asset-id")) +
                tlv(1, byteArrayOf(0, 0, 0, 1))),
            parameter(8, byteArrayOf(1))
        )
        val result = schema.inspect(packet)
        assertEquals(Iap2CarPlayStartSchema.Result.STRUCTURE_OBSERVED, result.result)
        assertTrue(result.hasUsableKnownFields)
        assertTrue(result.fieldNames.containsAll(setOf(
            "usbIP", "ssid", "pass", "channel", "wifiIP",
            "securityType", "port", "deviceID", "assetID", "assetVer", "mutualAuth"
        )))
        assertEquals(1, result.usbIpCount)
        assertEquals(1, result.wifiIpCount)
        assertFalse(result.toString().contains("SECRET_PASSWORD"))
        assertFalse(result.toString().contains("PRIVATE_DEVICE_ID"))
        assertFalse(result.toString().contains("192.0.2.10"))
    }

    @Test fun duplicateIpsRemainArraysAndScalarDuplicatesAreCounted() {
        val message = start(
            parameter(0, tlv(0, cString("192.0.2.1")) + tlv(0, cString("192.0.2.2"))),
            parameter(1, tlv(3, cString("192.0.2.3")) +
                tlv(3, cString("192.0.2.4")) +
                tlv(0, cString("First")) + tlv(0, cString("Second")))
        )
        val v = schema.inspect(message)
        assertEquals(Iap2CarPlayStartSchema.Result.STRUCTURE_OBSERVED, v.result)
        assertEquals(2, v.usbIpCount)
        assertEquals(2, v.wifiIpCount)
        assertEquals(1, v.duplicateScalarCount)
    }

    @Test fun unknownPathsAreReportedNotMisinterpreted() {
        val msg = start(
            parameter(1, tlv(44, byteArrayOf(1, 2)) +
                tlv(3, cString("192.0.2.5"))),
            parameter(80, byteArrayOf(4))
        )
        val got = schema.inspect(msg)
        assertEquals(Iap2CarPlayStartSchema.Result.STRUCTURE_OBSERVED, got.result)
        assertEquals(2, got.unknownCount)
        assertEquals(setOf("wifiIP"), got.fieldNames)
        assertEquals(Iap2CarPlayStartSchema.Result.UNKNOWN_ONLY,
            schema.inspect(start(parameter(33, byteArrayOf(1)))).result)
    }

    @Test fun malformedNestedLengthIsRejectedWithoutBufferOverread() {
        assertEquals(Iap2CarPlayStartSchema.Result.MALFORMED_NESTED_TLV,
            schema.inspect(start(parameter(1, byteArrayOf(0, 3, 0, 0)))).result)
        assertEquals(Iap2CarPlayStartSchema.Result.MALFORMED_NESTED_TLV,
            schema.inspect(start(parameter(7, byteArrayOf(0, 12, 0, 1, 1)))).result)
        assertEquals(Iap2CarPlayStartSchema.Result.MALFORMED_NESTED_TLV,
            schema.inspect(start(parameter(0, byteArrayOf(0, 4, 0, 0, 1)))).result)
    }

    @Test fun invalidBooleanAndUIntWidthsFailClosed() {
        assertEquals(Iap2CarPlayStartSchema.Result.MALFORMED_FIELD,
            schema.inspect(start(parameter(8, byteArrayOf(2)))).result)
        assertEquals(Iap2CarPlayStartSchema.Result.MALFORMED_FIELD,
            schema.inspect(start(parameter(2, byteArrayOf(0, 0x1f, 0x90.toByte())))).result)
        assertEquals(Iap2CarPlayStartSchema.Result.MALFORMED_FIELD,
            schema.inspect(start(parameter(1, tlv(2, byteArrayOf(0, 6))))).result)
    }

    @Test fun missingNulAndCorruptUtf8CannotBeAcceptedAsCredentials() {
        assertEquals(Iap2CarPlayStartSchema.Result.MALFORMED_FIELD,
            schema.inspect(start(parameter(1, tlv(1, "NO_TERMINATOR".toByteArray())))).result)
        assertEquals(Iap2CarPlayStartSchema.Result.MALFORMED_FIELD,
            schema.inspect(start(parameter(1, tlv(1, byteArrayOf(0xC3.toByte(), 0))))).result)
        assertEquals(Iap2CarPlayStartSchema.Result.MALFORMED_FIELD,
            schema.inspect(start(parameter(1, tlv(1, byteArrayOf(65, 0, 66, 0))))).result)
    }

    @Test fun emptyAndWrongMessageAreNeverStartSession() {
        assertEquals(Iap2CarPlayStartSchema.Result.EMPTY,
            schema.inspect(start()).result)
        assertEquals(Iap2CarPlayStartSchema.Result.NOT_START_SESSION,
            schema.inspect(Iap2ControlSessionCodec.Message(
                Iap2ControlSessionCodec.CARPLAY_AVAILABILITY,
                listOf(parameter(1, cString("irrelevant")))
            )).result)
    }

    @Test fun streamingFragmentsStayUnacceptedUntilCompleteValidatedMessage() {
        val full = Iap2ControlSessionCodec.encode(start(
            parameter(1, tlv(1, cString("SECRET"))),
            parameter(8, byteArrayOf(1))
        ))
        val stream = Iap2ControlStreamParser()
        assertTrue(stream.accept(full.copyOfRange(0, 7)).isEmpty())
        assertTrue(stream.accept(full.copyOfRange(7, 13)).isEmpty())
        val received = stream.accept(full.copyOfRange(13, full.size))
        assertEquals(1, received.size)
        assertEquals(Iap2CarPlayStartSchema.Result.STRUCTURE_OBSERVED,
            schema.inspect(received.single()).result)
        assertFalse(schema.inspect(received.single()).toString().contains("SECRET"))
    }
}
