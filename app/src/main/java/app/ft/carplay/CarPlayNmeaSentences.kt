package app.ft.carplay

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * FT200.6 standards-based NMEA GGA/RMC serialization only. Toyota's actual
 * CarPlay iAP2/PASCD path and subscription must still be verified in final
 * HU tests. NO Bluetooth send, raw location logs or phone GPS fallback here.
 */
internal object CarPlayNmeaSentences {
    class PairOfSentences(val gga: String, val rmc: String) {
        override fun toString(): String =
            "CarPlayNmeaSentences(GGA+RMC, location redacted)"
    }

    data class Fix(
        val latitude: Double, val longitude: Double,
        val utcEpochMillis: Long,
        val source: CarPlayMediaPreflight.PositionSource,
        val verifiedFusion: Boolean,
        val speedMps: Double? = null,
        val courseDegrees: Double? = null,
        val altitudeMetres: Double? = null,
        val satellitesUsed: Int? = null,
        val hdop: Double? = null
    )

    fun encodeIfApproved(
        gate: CarPlayMediaPreflight.GpsResult,
        fix: Fix,
        talker: String = "GP"
    ): PairOfSentences? {
        if (gate != CarPlayMediaPreflight.GpsResult.READY_TO_ENCODE_NOT_SENT) return null
        if (talker != "GP" && talker != "GN") return null
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 ||
            fix.latitude == 0.0 && fix.longitude == 0.0) return null
        if (fix.utcEpochMillis !in 0L..4102444800000L) return null
        if (fix.source == CarPlayMediaPreflight.PositionSource.PHONE ||
            fix.source == CarPlayMediaPreflight.PositionSource.UNKNOWN) return null
        if (fix.source == CarPlayMediaPreflight.PositionSource.VERIFIED_EKF_DR &&
            !fix.verifiedFusion) return null

        val utc = Instant.ofEpochMilli(fix.utcEpochMillis).atOffset(ZoneOffset.UTC)
        val time = String.format(Locale.US, "%02d%02d%02d.%03d",
            utc.hour, utc.minute, utc.second, utc.nano / 1_000_000)
        val date = String.format(Locale.US, "%02d%02d%02d",
            utc.dayOfMonth, utc.monthValue, utc.year % 100)
        val latitude = coordinate(fix.latitude, longitude = false)
        val longitude = coordinate(fix.longitude, longitude = true)

        val estimated = fix.source == CarPlayMediaPreflight.PositionSource.VERIFIED_EKF_DR
        val mode = if (estimated) "E" else "A"
        val quality = if (estimated) 6 else 1
        val speed = fix.speedMps?.takeIf { it.isFinite() && it in 0.0..65.0 }
        val knots = speed?.let { String.format(Locale.US, "%.2f", it * 1.94384449244) } ?: ""
        val course = if (speed != null && speed >= 1.0)
            fix.courseDegrees?.takeIf { it.isFinite() && it in 0.0..360.0 }
                ?.let { String.format(Locale.US, "%.1f", it % 360.0) } ?: ""
            else ""
        val satellites = fix.satellitesUsed?.takeIf { it in 0..99 }
            ?.let { String.format(Locale.US, "%02d", it) } ?: ""
        val hdop = fix.hdop?.takeIf { it.isFinite() && it in 0.0..99.9 }
            ?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        val altitude = fix.altitudeMetres?.takeIf { it.isFinite() && it in -1000.0..20000.0 }
            ?.let { String.format(Locale.US, "%.1f", it) } ?: ""

        // GGA includes empty geoid separation/unit and differential fields
        // rather than fabricating a zero geoid, age or station identifier.
        val gga = sentence("${talker}GGA,$time,${latitude.first},${latitude.second}," +
            "${longitude.first},${longitude.second},$quality,$satellites,$hdop,$altitude,M,,,,")
        val rmc = sentence("${talker}RMC,$time,A,${latitude.first},${latitude.second}," +
            "${longitude.first},${longitude.second},$knots,$course,$date,,,$mode")
        return PairOfSentences(gga, rmc)
    }

    private fun coordinate(value: Double, longitude: Boolean): Pair<String, String> {
        val magnitude = abs(value)
        var degrees = floor(magnitude).toInt()
        var minutes = (magnitude - degrees) * 60.0
        minutes = kotlin.math.round(minutes * 10000.0) / 10000.0
        if (minutes >= 60.0) { degrees += 1; minutes = 0.0 }
        val direction = if (longitude) { if (value < 0.0) "W" else "E" }
                        else { if (value < 0.0) "S" else "N" }
        return String.format(Locale.US,
            if (longitude) "%03d%07.4f" else "%02d%07.4f",
            degrees, minutes) to direction
    }

    private fun sentence(body: String): String {
        var xor = 0
        for (char in body) xor = xor xor char.code
        return "\$" + body + "*" + String.format(Locale.US, "%02X", xor) + "\r\n"
    }
}
