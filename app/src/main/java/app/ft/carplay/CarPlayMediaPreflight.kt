package app.ft.carplay

/**
 * FT200.6 conservative source-only probes. Never copy the ICCOA direct H264
 * path into AirPlay without observed peer packetization/encryption capability.
 * No decoder, encoder, GPS provider switch or CAN/DCM/VHAL writes.
 */
internal object CarPlayMediaPreflight {
    enum class VideoResult {
        PEER_UNVERIFIED, SPS_OR_IDR_MISSING, FORMAT_INCOMPATIBLE,
        AIRPLAY_PACKETIZATION_UNKNOWN, AIRPLAY_ENCRYPTION_UNKNOWN,
        DIRECT_H264_CANDIDATE_ONLY
    }

    data class Video(
        val width: Int, val height: Int, val fps: Int,
        val profile: Int, val level: Int, val spsPps: Boolean, val idr: Boolean
    )
    data class Receiver(
        val width: Int, val height: Int, val maxFps: Int,
        val profiles: Set<Int>, val maxLevel: Int,
        val packetizationProven: Boolean, val encryptionProven: Boolean
    )

    fun video(source: Video?, receiver: Receiver?): VideoResult {
        if (receiver == null) return VideoResult.PEER_UNVERIFIED
        if (source == null || !source.spsPps || !source.idr)
            return VideoResult.SPS_OR_IDR_MISSING
        if (source.width != receiver.width || source.height != receiver.height ||
            source.width !in 320..4096 || source.height !in 240..2160 ||
            source.fps !in 1..60 || source.fps > receiver.maxFps ||
            source.profile !in receiver.profiles || source.level > receiver.maxLevel)
            return VideoResult.FORMAT_INCOMPATIBLE
        if (!receiver.packetizationProven) return VideoResult.AIRPLAY_PACKETIZATION_UNKNOWN
        if (!receiver.encryptionProven) return VideoResult.AIRPLAY_ENCRYPTION_UNKNOWN
        return VideoResult.DIRECT_H264_CANDIDATE_ONLY
    }

    enum class PositionSource { REAL_OEM_WGS84, VERIFIED_EKF_DR, PHONE, UNKNOWN }
    enum class GpsResult {
        IAP2_NOT_AUTHENTICATED, HU_DID_NOT_SUBSCRIBE,
        NMEA_FORMAT_NOT_PROVEN, INVALID_COORDINATES, PHONE_FALLBACK_FORBIDDEN,
        UNVERIFIED_DR, SOURCE_UNKNOWN, READY_TO_ENCODE_NOT_SENT
    }

    data class Fix(
        val latitude: Double, val longitude: Double,
        val source: PositionSource, val wgs84: Boolean, val ekfVerified: Boolean
    )

    fun gps(authenticated: Boolean, huSubscribed: Boolean,
        negotiatedFormat: String?, fix: Fix?): GpsResult {
        if (!authenticated) return GpsResult.IAP2_NOT_AUTHENTICATED
        if (!huSubscribed) return GpsResult.HU_DID_NOT_SUBSCRIBE
        if (negotiatedFormat != "NMEA_GGA_RMC_CONFIRMED")
            return GpsResult.NMEA_FORMAT_NOT_PROVEN
        if (fix == null || !fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 ||
            !fix.wgs84 || (fix.latitude == 0.0 && fix.longitude == 0.0))
            return GpsResult.INVALID_COORDINATES
        return when (fix.source) {
            PositionSource.REAL_OEM_WGS84 -> GpsResult.READY_TO_ENCODE_NOT_SENT
            PositionSource.VERIFIED_EKF_DR -> if (fix.ekfVerified)
                GpsResult.READY_TO_ENCODE_NOT_SENT else GpsResult.UNVERIFIED_DR
            PositionSource.PHONE -> GpsResult.PHONE_FALLBACK_FORBIDDEN
            PositionSource.UNKNOWN -> GpsResult.SOURCE_UNKNOWN
        }
    }
}
