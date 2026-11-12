package app.ft.dr

import app.ft.core.GpsSourceMode
import app.ft.core.VehicleLocation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Exact in-process producer/consumer contract for FT-native and subscribed AA.
 * Real OEM wheel/yaw/accuracy/clock provenance must still be proven separately.
 */
class Ft179GnssDrPipelineIntegrationTest {
    @Before fun reset() {
        FtPositionTelemetry.clear()
        DrNavigationTelemetry.clear()
    }

    private fun raw(receivedAt: Long, latitude: Double = 52.0, seq: Long = 1L) =
        VehicleLocation(
            source = "ICCOA",
            receivedAtMs = receivedAt,
            positionAtMs = receivedAt,
            latitude = latitude,
            longitude = 13.0,
            coordinatesUnit = "ANDROID_LOCATION_WGS84",
            gpsPacketSequence = seq
        )

    private fun gps(t: Long) = FtDrFusionEngine.Gnss(
        latitude = 52.0, longitude = 13.0,
        horizontalAccuracyMetres = 2.0, courseDegrees = 90.0,
        fixMonotonicMs = t, emittedAtMs = t + 100_000L,
        source = GpsSourceMode.CAR,
        genuineCarFix = true, accuracyVerified = true,
        courseVerified = true, fixTimeVerified = true
    )

    private fun wheel(t: Long) = FtDrFusionEngine.Motion(
        monotonicMs = t, signedWheelSpeedMps = 10.0,
        yawRateRadiansPerSecond = 0.0,
        wheelUnitsVerified = true,
        yawAxisAndUnitsVerified = true,
        gearDirectionVerified = true
    )

    @Test fun gnssOnlyIsImmediatelyAvailableToFtWithoutPretendingFusion() {
        assertTrue(FtPositionTelemetry.acceptRaw(raw(101_000L), 101_000L))
        val fusion = FtDrFusionEngine { _, _, _, _ ->
            fail("No verified wheel prediction must mean no FUSED publication")
            false
        }
        assertTrue(fusion.onGnss(gps(1_000L)))
        assertTrue(fusion.onGnss(gps(1_100L)))
        assertEquals(FtNavigationPosition.Source.RAW_UNFILTERED,
            FtPositionTelemetry.current.value!!.source)
        assertNull(DrNavigationTelemetry.current.value)
    }

    @Test fun verifiedVehiclePredictionPromotesSingleSharedFtAndAaFeed() {
        val fusion = FtDrFusionEngine { lat, lon, estimate, wall ->
            DrNavigationTelemetry.publishFromVerifiedEkf(
                referenceLatitude = lat,
                referenceLongitude = lon,
                estimate = estimate,
                gpsSourceMode = GpsSourceMode.CAR,
                wheelSpeedAndYawVerified = true,
                producedAtMs = wall
            )
        }
        assertTrue(FtPositionTelemetry.acceptRaw(raw(101_000L), 101_000L))
        assertTrue(fusion.onGnss(gps(1_000L)))
        assertNull(DrNavigationTelemetry.current.value)
        assertFalse(fusion.onMotion(wheel(1_000L), 101_000L))
        assertTrue(fusion.onMotion(wheel(1_100L), 101_100L))
        val fused = requireNotNull(DrNavigationTelemetry.current.value)
        assertTrue(FtPositionTelemetry.acceptFused(fused, 101_100L))
        val shared = requireNotNull(FtPositionTelemetry.current.value)
        assertEquals(FtNavigationPosition.Source.FUSED, shared.source)
        // Heading 90 degrees means EAST, not north. In local ENU this
        // advances longitude while latitude remains at the GNSS anchor.
        assertEquals(52.0, shared.latitude!!, 0.000001)
        assertTrue(shared.longitude!! > 13.0)
        assertFalse(FtPositionTelemetry.acceptRaw(
            raw(101_200L, latitude = 52.005, seq = 2L),
            101_200L
        ))
        assertEquals(FtNavigationPosition.Source.FUSED,
            FtPositionTelemetry.current.value!!.source)
    }

    @Test fun noMotionVerificationCannotTriggerFusedOrPhoneFallback() {
        val fusion = FtDrFusionEngine { _, _, _, _ -> false }
        assertTrue(fusion.onGnss(gps(1_000L)))
        assertFalse(fusion.onMotion(
            wheel(1_100L).copy(yawAxisAndUnitsVerified = false), 101_100L))
        assertFalse(fusion.onGnss(gps(1_200L).copy(
            source = GpsSourceMode.PHONE)))
        assertNull(DrNavigationTelemetry.current.value)
        assertNull(FtPositionTelemetry.current.value)
    }
}
