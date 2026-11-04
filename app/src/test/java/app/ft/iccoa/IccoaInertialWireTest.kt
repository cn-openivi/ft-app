package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

/**
 * Canonical protobuf bytes from the OEM ucar.proto field numbers/wire types.
 * These are synthetic fixtures, not unverified captures from the owner car.
 */
class IccoaInertialWireTest {
    private fun hex(input: String): ByteArray = input.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()

    // GyroScope: gyro_type=2; x=1.0; y=-2.0; z=3.5; timestamp=1000
    private val gyro = hex("""
        08 02
        11 00 00 00 00 00 00 f0 3f
        19 00 00 00 00 00 00 00 c0
        21 00 00 00 00 00 00 0c 40
        28 e8 07
    """.trimIndent())

    // Acceleration: x=1.0; y=-2.0; z=3.5; timestamp=1000
    private val acceleration = hex("""
        09 00 00 00 00 00 00 f0 3f
        11 00 00 00 00 00 00 00 c0
        19 00 00 00 00 00 00 0c 40
        20 e8 07
    """.trimIndent())

    @Test fun gyroDirectMatchesOemFieldNumbersAndExactValues() {
        val sample = requireNotNull(IccoaInertialWire.decodeGyro(gyro))
        assertEquals(2, sample.gyroType)
        assertEquals(1.0, sample.x, 0.0)
        assertEquals(-2.0, sample.y, 0.0)
        assertEquals(3.5, sample.z, 0.0)
        assertEquals(1000L, sample.deviceTimestampRaw)
    }

    @Test fun accelerationDirectMatchesOemFieldNumbersAndExactValues() {
        val sample = requireNotNull(IccoaInertialWire.decodeAcceleration(acceleration))
        assertNull(sample.gyroType)
        assertEquals(1.0, sample.x, 0.0)
        assertEquals(-2.0, sample.y, 0.0)
        assertEquals(3.5, sample.z, 0.0)
        assertEquals(1000L, sample.deviceTimestampRaw)
    }

    @Test fun decoderAcceptsMatchingSensorIndexWrapperOnly() {
        val wrappedGyro = byteArrayOf(0x1a, gyro.size.toByte()) + gyro
        val wrappedAccel = byteArrayOf(0x22, acceleration.size.toByte()) + acceleration
        assertEquals(3.5, IccoaInertialWire.decodeGyro(wrappedGyro)!!.z, 0.0)
        assertEquals(-2.0, IccoaInertialWire.decodeAcceleration(wrappedAccel)!!.y, 0.0)
        assertNull(IccoaInertialWire.decodeGyro(wrappedAccel))
        assertNull(IccoaInertialWire.decodeAcceleration(wrappedGyro))
    }

    @Test fun zeroAxesMayBeOmittedByProto3Defaults() {
        val sample = IccoaInertialWire.decodeAcceleration(hex("20 e8 07"))
        assertNotNull(sample)
        assertEquals(0.0, sample!!.x, 0.0)
        assertEquals(0.0, sample.y, 0.0)
        assertEquals(0.0, sample.z, 0.0)
    }

    @Test fun incompleteDuplicateAndNonFinitePacketsAreRejected() {
        assertNull(IccoaInertialWire.decodeGyro(byteArrayOf()))
        assertNull(IccoaInertialWire.decodeGyro(hex("08 02"))) // no axes/timestamp
        assertNull(IccoaInertialWire.decodeGyro(gyro.copyOf(gyro.size - 1)))
        assertNull(IccoaInertialWire.decodeGyro(gyro + gyro.copyOfRange(2, 11)))
        val nanX = hex("09 00 00 00 00 00 00 f8 7f 20 e8 07")
        assertNull(IccoaInertialWire.decodeAcceleration(nanX))
        assertNull(IccoaInertialWire.decodeAcceleration(ByteArray(513)))
    }
}
