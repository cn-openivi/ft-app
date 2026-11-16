package app.ft.dr

import app.ft.core.VehicleLocation
import org.junit.Assert.*
import org.junit.Test

class CarTunnelKalmanTest {
    private fun fix(
        lat: Double = 55.7, lon: Double = 37.6,
        speedFlooredMps: Long? = 10L, course: Long? = 90L
    ) = VehicleLocation(
        source = "ICCOA", receivedAtMs = 1_000L,
        positionAtMs = 1_000L, latitude = lat, longitude = lon,
        coordinatesUnit = "ANDROID_LOCATION_WGS84",
        speedRaw = speedFlooredMps, headingRaw = course,
        speedUnit = "ANDROID_LOCATION_MPS_FLOORED",
        headingUnit = "ANDROID_LOCATION_DEGREES_FLOORED"
    )

    @Test fun genuineGpsContinuouslyAnchorsExistingTinyEkfAndTunnelCourseHoldIsProvisional() {
        val f = CarTunnelKalman()
        f.beginSession()
        val first = f.onGps(fix(), 1_000L)!!
        assertEquals(CarTunnelKalman.Mode.GPS_OBSERVED, first.mode)
        assertEquals(55.7, first.latitude, 1e-8)
        assertFalse(first.independentlyVerifiedDeadReckoning)
        assertFalse(first.eligibleForAaSensorLocation)
        assertTrue(first.modelAccuracyNotReceiverAccuracy)
        assertNull(f.onGear(36L, 2L, 1_100L))
        val second = f.onGear(36L, 2L, 2_100L)!!
        assertTrue(second.longitude > first.longitude)
        assertEquals(0.0, second.latitude - first.latitude, 1e-4)
        // Live, true OEM GPS corrects the SAME filter; no second GPS engine.
        assertNotNull(f.onGps(fix(lon = 37.60025), 2_500L))
        for (time in 3_000L..13_000L step 1_000L)
            assertNotNull(f.onGear(36L, 2L, time))
        val underground = f.current.value!!
        assertEquals(CarTunnelKalman.Mode.TUNNEL_COURSE_HELD, underground.mode)
        assertTrue(underground.gpsReceiptGapMs > 3_000L)
        assertTrue(underground.travelledModelMetres > 80.0)
        assertTrue(underground.longitude > 37.60025)
        assertTrue(underground.modelStdMetres >= 30.0)
        assertFalse(underground.eligibleForAaSensorLocation)
        assertTrue(f.evidence().reportLine().contains("estimatedFixForwardedToAa=NO"))
    }

    @Test fun stationaryAndNoGpsCourseNeverCreateAnInventedVehicleHeading() {
        val f = CarTunnelKalman()
        f.beginSession()
        val first = f.onGps(fix(speedFlooredMps = 0L, course = 0L), 1_000L)!!
        assertEquals(CarTunnelKalman.Mode.GPS_NO_COURSE, first.mode)
        assertNull(first.courseDegrees)
        assertNull(f.onGear(0L, 2L, 1_100L))
        val parked = f.onGear(0L, 2L, 2_100L)!!
        assertEquals(first.latitude, parked.latitude, 1e-8)
        assertEquals(first.longitude, parked.longitude, 1e-8)
        assertEquals(0.0, parked.travelledModelMetres, 0.0)
        // Without a measured course, a positive speed must grow covariance
        // but must NEVER imply northbound travel from internal EKF heading 0.
        val unknown = f.onGear(20L, 2L, 3_100L)!!
        assertNull(unknown.courseDegrees)
        assertEquals(first.latitude, unknown.latitude, 1e-8)
        assertEquals(0.0, unknown.travelledModelMetres, 0.0)
        assertTrue(f.evidence().noHeadingOrDirectionIntervals > 0L)
    }

    @Test fun unknownOrReverseGearDoesNotSilentlyMoveForwardThroughParkingTurn() {
        val f = CarTunnelKalman()
        f.beginSession()
        f.onGps(fix(), 1_000L)
        assertNull(f.onGear(18L, 2L, 1_100L))
        val forward = f.onGear(18L, 2L, 2_100L)!!
        val next = f.onGear(18L, 3L, 4_200L)!!
        assertEquals(forward.travelledModelMetres, next.travelledModelMetres, 1e-6)
        val blocked = f.onGear(18L, 3L, 5_200L)!!
        assertEquals(forward.longitude, blocked.longitude, 1e-8)
        assertEquals(CarTunnelKalman.Mode.TUNNEL_NO_DIRECTION, blocked.mode)
        assertTrue(f.evidence().gearTimeGaps >= 1L)
    }

    @Test fun modelledCourseCorrectsWhenRealGpsReturnsAndReinitializesOnReconnect() {
        val f = CarTunnelKalman()
        f.beginSession()
        f.onGps(fix(), 1_000L)
        f.onGear(36L, 2L, 1_100L)
        for (t in 2_100L..8_100L step 1_000L) f.onGear(36L, 2L, t)
        val before = f.current.value!!
        assertEquals(CarTunnelKalman.Mode.TUNNEL_COURSE_HELD, before.mode)
        val returned = f.onGps(fix(lat = 55.7001, lon = 37.6010,
            speedFlooredMps = 8L, course = 270L), 8_500L)!!
        assertEquals(CarTunnelKalman.Mode.GPS_OBSERVED, returned.mode)
        assertEquals(0L, returned.gpsReceiptGapMs)
        assertTrue(f.evidence().gpsAccepted == 2L)
        f.endSession()
        assertNull(f.current.value)
        assertFalse(f.evidence().active)
        assertNull(f.onGear(30L, 2L, 9_000L))
        f.beginSession()
        assertEquals(0L, f.evidence().gpsAccepted)
        assertEquals(CarTunnelKalman.Mode.NO_GPS, f.evidence().mode)
        assertNull(f.current.value)
    }

    @Test fun rejectsNullIslandBaiduStaleMonotonicAndInvalidGearWithoutTeleport() {
        val f = CarTunnelKalman()
        f.beginSession()
        assertNull(f.onGps(fix(lat = 0.0, lon = 0.0), 1_000L))
        assertNull(f.onGps(fix().copy(source = "BAIDU"), 1_000L))
        assertEquals(CarTunnelKalman.Mode.NO_GPS, f.evidence().mode)
        assertNotNull(f.onGps(fix(), 1_000L))
        assertNull(f.onGps(fix(lon = 37.61), 900L))
        assertNull(f.onGear(235L, 2L, 1_100L))
        assertNull(f.onGear(-1L, 2L, 1_200L))
        assertEquals(0L, f.evidence().gearAccepted)
        assertNull(f.onGear(30L, 2L, 1_300L))
        val forward = f.onGear(30L, 2L, 2_300L)!!
        assertTrue(forward.longitude > 37.6)
        assertTrue(f.evidence().reportLine().contains("carlinkFaultInferredFromSilence=NO"))
    }

    @Test fun newGpsWithNoNewDistanceCanUpdateKalmanWithoutUnverifiedYawPromotion() {
        val f = CarTunnelKalman()
        f.beginSession()
        f.onGps(fix(speedFlooredMps = 0L, course = null), 1_000L)
        f.onGear(0L, 2L, 1_100L)
        f.onGear(0L, 2L, 2_100L)
        val later = f.onGps(fix(lat = 55.70001, lon = 37.60001,
            speedFlooredMps = 3L, course = 0L), 3_000L)
        assertNotNull(later)
        assertNotNull(later!!.courseDegrees)
        assertFalse(later.eligibleForAaSensorLocation)
        assertEquals(0L, f.evidence().predictedForwardIntervals)
    }
}
