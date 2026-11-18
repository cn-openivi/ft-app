package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayMediaPreflightTest {
    private val policy = CarPlayMediaPreflight
    private val source = CarPlayMediaPreflight.Video(1920,1080,30,100,42,true,true)
    private val peer = CarPlayMediaPreflight.Receiver(1920,1080,30,setOf(100),
        42,true,true)

    @Test fun absentAirPlayPeerNeverEnablesH264DirectRoute() {
        assertEquals(CarPlayMediaPreflight.VideoResult.PEER_UNVERIFIED,
            policy.video(source, null))
        assertEquals(CarPlayMediaPreflight.VideoResult.SPS_OR_IDR_MISSING,
            policy.video(source.copy(idr = false), peer))
    }

    @Test fun mismatchedVideoOrUnknownEncryptionStaysBlocked() {
        assertEquals(CarPlayMediaPreflight.VideoResult.FORMAT_INCOMPATIBLE,
            policy.video(source.copy(fps = 60),peer))
        assertEquals(CarPlayMediaPreflight.VideoResult.AIRPLAY_PACKETIZATION_UNKNOWN,
            policy.video(source, peer.copy(packetizationProven = false)))
        assertEquals(CarPlayMediaPreflight.VideoResult.AIRPLAY_ENCRYPTION_UNKNOWN,
            policy.video(source, peer.copy(encryptionProven = false)))
        assertEquals(CarPlayMediaPreflight.VideoResult.DIRECT_H264_CANDIDATE_ONLY,
            policy.video(source,peer))
    }

    private val carFix = CarPlayMediaPreflight.Fix(52.1,4.4,
        CarPlayMediaPreflight.PositionSource.REAL_OEM_WGS84,true,false)

    @Test fun carGpsRequiresRealAuthenticatedHuAndSubscription() {
        assertEquals(CarPlayMediaPreflight.GpsResult.IAP2_NOT_AUTHENTICATED,
            policy.gps(false,true,"NMEA_GGA_RMC_CONFIRMED",carFix))
        assertEquals(CarPlayMediaPreflight.GpsResult.HU_DID_NOT_SUBSCRIBE,
            policy.gps(true,false,"NMEA_GGA_RMC_CONFIRMED",carFix))
        assertEquals(CarPlayMediaPreflight.GpsResult.NMEA_FORMAT_NOT_PROVEN,
            policy.gps(true,true,"UNVERIFIED_PASCD",carFix))
    }

    @Test fun noPhoneFallbackAndNoInventedDeadReckoning() {
        fun gps(f: CarPlayMediaPreflight.Fix?) =
            policy.gps(true,true,"NMEA_GGA_RMC_CONFIRMED",f)
        assertEquals(CarPlayMediaPreflight.GpsResult.READY_TO_ENCODE_NOT_SENT,
            gps(carFix))
        assertEquals(CarPlayMediaPreflight.GpsResult.PHONE_FALLBACK_FORBIDDEN,
            gps(carFix.copy(source = CarPlayMediaPreflight.PositionSource.PHONE)))
        assertEquals(CarPlayMediaPreflight.GpsResult.UNVERIFIED_DR,
            gps(carFix.copy(source = CarPlayMediaPreflight.PositionSource.VERIFIED_EKF_DR)))
        assertEquals(CarPlayMediaPreflight.GpsResult.READY_TO_ENCODE_NOT_SENT,
            gps(carFix.copy(source = CarPlayMediaPreflight.PositionSource.VERIFIED_EKF_DR,
                ekfVerified = true)))
        assertEquals(CarPlayMediaPreflight.GpsResult.INVALID_COORDINATES,
            gps(carFix.copy(latitude = 0.0, longitude = 0.0)))
    }
}
