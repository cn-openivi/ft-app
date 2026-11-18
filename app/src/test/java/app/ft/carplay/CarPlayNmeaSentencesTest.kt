package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayNmeaSentencesTest {
    private val eligible = CarPlayMediaPreflight.GpsResult.READY_TO_ENCODE_NOT_SENT
    private val fix = CarPlayNmeaSentences.Fix(
        latitude = 52.1, longitude = 4.4,
        utcEpochMillis = 1732019696000L,
        source = CarPlayMediaPreflight.PositionSource.REAL_OEM_WGS84,
        verifiedFusion = false, speedMps = 10.0,
        courseDegrees = 271.0, altitudeMetres = 4.2, altitudeIsMsl = true,
        satellitesUsed = 11, hdop = 1.2
    )

    @Test fun exactGgaAndRmcChecksumsForVerifiedWgs84() {
        val result = CarPlayNmeaSentences.encodeIfApproved(eligible, fix)!!
        assertEquals("\$GPGGA,123456.000,5206.0000,N,00424.0000,E,1,11,1.2,4.2,M,,,,*0E\r\n",
            result.gga)
        assertEquals("\$GPRMC,123456.000,A,5206.0000,N,00424.0000,E,19.44,271.0,191124,,,A*68\r\n",
            result.rmc)
        assertFalse(result.toString().contains("5206"))
        assertFalse(fix.toString().contains("52.1"))
    }

    @Test fun ellipsoidalOrUnknownVerticalDatumIsNeverInventedAsMsl() {
        val result = CarPlayNmeaSentences.encodeIfApproved(
            eligible, fix.copy(altitudeIsMsl = false))!!
        assertTrue(result.gga.contains(",11,1.2,,M,,,,"))
        assertFalse(result.gga.contains("4.2,M"))
    }

    @Test fun signedLatitudeLongitudeAndTalkerGnArePreserved() {
        val result = CarPlayNmeaSentences.encodeIfApproved(eligible,
            fix.copy(latitude = -33.86, longitude = -151.21), talker = "GN")!!
        assertTrue(result.gga.startsWith("\$GNGGA,"))
        assertTrue(result.gga.contains(",3351.6000,S,15112.6000,W,"))
    }

    @Test fun verifiedFusedDrIsClearlyMarkedEstimated() {
        val result = CarPlayNmeaSentences.encodeIfApproved(eligible,fix.copy(
            source = CarPlayMediaPreflight.PositionSource.VERIFIED_EKF_DR,
            verifiedFusion = true))!!
        assertTrue(result.gga.contains(",E,6,"))
        assertTrue(result.rmc.trim().substringBefore('*').endsWith(",,,E"))
    }

    @Test fun noNmeaBeforeIap2AuthenticationOrPhoneFallback() {
        assertNull(CarPlayNmeaSentences.encodeIfApproved(
            CarPlayMediaPreflight.GpsResult.IAP2_NOT_AUTHENTICATED, fix))
        assertNull(CarPlayNmeaSentences.encodeIfApproved(eligible,fix.copy(
            source = CarPlayMediaPreflight.PositionSource.PHONE)))
        assertNull(CarPlayNmeaSentences.encodeIfApproved(eligible,fix.copy(
            source = CarPlayMediaPreflight.PositionSource.VERIFIED_EKF_DR,
            verifiedFusion = false)))
    }

    @Test fun invalidCoordinatesOrTalkerAreRejected() {
        assertNull(CarPlayNmeaSentences.encodeIfApproved(eligible,
            fix.copy(latitude = 91.0)))
        assertNull(CarPlayNmeaSentences.encodeIfApproved(eligible,
            fix.copy(latitude = 0.0, longitude = 0.0)))
        assertNull(CarPlayNmeaSentences.encodeIfApproved(eligible,fix,talker = "XX"))
        assertNotNull(CarPlayNmeaSentences.encodeIfApproved(eligible,fix))
    }
}
