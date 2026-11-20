package app.ft.carplay

import java.nio.charset.StandardCharsets

/**
 * Passive iAP2 CONTROL LocationInformation 0xFFFB envelope inspector.
 * Source-only: no GGA/PASCD transmission, no subscription, no DR usage proof.
 * PAGCD/PAACD are classified as opaque NMEA: axes/units/timebase UNKNOWN.
 * Only a redacted sentence family is returned, never vehicle data.
 */
internal object CarPlayLocationWireInspector {
    const val LOCATION_INFORMATION = 0xFFFB
    enum class Kind {
        NOT_LOCATION_INFORMATION, INVALID_WIRE,
        GPS_GGA_RMC_OPAQUE, SPEED_PASCD_OPAQUE,
        GYRO_PAGCD_OPAQUE, ACCEL_PAACD_OPAQUE, OTHER_NMEA_OPAQUE
    }

    fun inspect(message: Iap2ControlSessionCodec.Message): Kind {
        if (message.id != LOCATION_INFORMATION) return Kind.NOT_LOCATION_INFORMATION
        if (message.parameters.size != 1 || message.parameters.single().id != 0)
            return Kind.INVALID_WIRE
        val raw = message.parameters.single().value
        if (raw.size !in 8..1024 || raw.last() != 0.toByte()) return Kind.INVALID_WIRE
        if (raw.dropLast(1).any {
                val byte = it.toInt() and 255
                byte !in 32..126 && byte != 10 && byte != 13
            }) return Kind.INVALID_WIRE
        val line = String(raw, 0, raw.size - 1, StandardCharsets.US_ASCII)
            .trimEnd('\r', '\n')
        if (!line.startsWith('$') || line.count { it == '$' } != 1 ||
            line.drop(1).any { it.code < 32 }) return Kind.INVALID_WIRE
        val star = line.indexOf('*')
        if (star < 3 || star != line.length - 3) return Kind.INVALID_WIRE
        val expected = line.substring(star + 1).toIntOrNull(16)
            ?: return Kind.INVALID_WIRE
        var checksum = 0
        for (ch in line.substring(1, star)) checksum = checksum xor ch.code
        if (checksum != expected) return Kind.INVALID_WIRE
        return when (line.substring(1, star).substringBefore(',')) {
            "GPGGA", "GNGGA", "GPRMC", "GNRMC" -> Kind.GPS_GGA_RMC_OPAQUE
            "PASCD" -> Kind.SPEED_PASCD_OPAQUE
            "PAGCD" -> Kind.GYRO_PAGCD_OPAQUE
            "PAACD" -> Kind.ACCEL_PAACD_OPAQUE
            else -> Kind.OTHER_NMEA_OPAQUE
        }
    }
}
