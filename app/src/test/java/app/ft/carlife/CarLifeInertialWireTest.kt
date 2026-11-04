package app.ft.carlife

import org.junit.Assert.*
import org.junit.Test

/**
 * Source: Baidu CarLife's original protobuf-generated fields.
 * These are synthetic exact-wire fixtures, not vehicle-captured bytes.
 */
class CarLifeInertialWireTest {
    private fun hex(value: String): ByteArray =
        value.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private val gyro = hex("""
        08 02
        11 00 00 00 00 00 00 f0 3f
        19 00 00 00 00 00 00 00 c0
        21 00 00 00 00 00 00 0c 40
        28 e8 07
    """.trimIndent())

    private val acceleration = hex("""
        09 00 00 00 00 00 00 f0 3f
        11 00 00 00 00 00 00 00 c0
        19 00 00 00 00 00 00 0c 40
        20 e8 07
    """.trimIndent())

    @Test fun baiduGyroMatchesRequiredProto2Fields() {
        val r = requireNotNull(CarLifeInertialWire.gyro(gyro))
        assertEquals(2, r.gyroType)
        assertEquals(1.0, r.x, 0.0)
        assertEquals(-2.0, r.y, 0.0)
        assertEquals(3.5, r.z, 0.0)
        assertEquals(1_000L, r.deviceTimestampRaw)
    }

    @Test fun baiduAccelerometerMatchesRequiredProto2Fields() {
        val r = requireNotNull(CarLifeInertialWire.acceleration(acceleration))
        assertNull(r.gyroType)
        assertEquals(1.0, r.x, 0.0)
        assertEquals(-2.0, r.y, 0.0)
        assertEquals(3.5, r.z, 0.0)
        assertEquals(1_000L, r.deviceTimestampRaw)
    }

    @Test fun proto2RequiresAllThreeAxesAndGyroTypeEvenIfZero() {
        assertNull(CarLifeInertialWire.gyro(hex("08 02 28 e8 07")))
        assertNull(CarLifeInertialWire.gyro(gyro.copyOfRange(2, gyro.size)))
        assertNull(CarLifeInertialWire.acceleration(hex("20 e8 07")))
        assertNull(CarLifeInertialWire.acceleration(acceleration.copyOfRange(9, acceleration.size)))
    }

    @Test fun rejectMalformedRepeatedAndNonFiniteSamples() {
        assertNull(CarLifeInertialWire.gyro(gyro.copyOf(gyro.size - 1)))
        assertNull(CarLifeInertialWire.gyro(gyro + gyro.copyOfRange(2, 11)))
        val nan = hex("09 00 00 00 00 00 00 f8 7f") +
            acceleration.copyOfRange(9, acceleration.size)
        assertNull(CarLifeInertialWire.acceleration(nan))
        assertNull(CarLifeInertialWire.acceleration(ByteArray(513)))
    }
}
