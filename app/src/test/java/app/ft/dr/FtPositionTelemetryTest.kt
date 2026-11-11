package app.ft.dr

import app.ft.core.CarInfo
import app.ft.core.GpsSourceMode
import app.ft.core.VehicleLocation
import app.ft.ui.car.CarGpsSourceSelector
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** FT-native location is PRIMARY. AA only serializes this shared feed. */
class FtPositionTelemetryTest {
    @Before fun reset() { FtPositionTelemetry.clear() }

    private fun raw(t: Long, lat: Double = 55.7000, seq: Long = 1L) =
        VehicleLocation(
            source = "ICCOA", receivedAtMs = t, positionAtMs = t,
            latitude = lat, longitude = 37.6000,
            coordinatesUnit = "ANDROID_LOCATION_WGS84",
            gpsPacketSequence = seq)

    private fun fused(t: Long, lat: Double = 55.70001) = DrNavigationFix(
        latitude = lat, longitude = 37.60001,
        accuracyMetres = 3.0, headingDegrees = 90.0,
        fromSelectedGps = GpsSourceMode.CAR,
        producedAtMs = t, estimated = true,
        verifiedVehicleMotion = true)

    @Test fun carNativeAppsReceiveRawBaselineBeforeVerifiedFusion() {
        assertTrue(FtPositionTelemetry.acceptRaw(raw(10_000L), 10_000L))
        val position = FtPositionTelemetry.current.value!!
        assertEquals(FtNavigationPosition.Source.RAW_UNFILTERED, position.source)
        assertEquals(55.7, position.latitude!!, 0.000001)
        val widget = CarGpsSourceSelector().selectFt(position, null, 10_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA, widget.source)
        assertTrue(FtPositionTelemetry.updates.replayCache.isEmpty())
    }

    @Test fun verifiedFusionTakesAuthorityAndBlocksNewReflectedGpsForEveryConsumer() {
        assertTrue(FtPositionTelemetry.acceptRaw(raw(10_000L), 10_000L))
        assertTrue(FtPositionTelemetry.acceptFused(fused(10_080L), 10_080L))
        // A 30m reflected GNSS shift arrives later than the fused prediction.
        assertFalse(FtPositionTelemetry.acceptRaw(
            raw(10_100L, 55.70027, 2L), 10_100L))
        val position = FtPositionTelemetry.current.value!!
        assertEquals(FtNavigationPosition.Source.FUSED, position.source)
        assertEquals(55.70001, position.latitude!!, 0.000001)
        val native = CarGpsSourceSelector().selectFt(position, null, 10_100L)
        assertEquals(CarGpsSourceSelector.Source.DR, native.source)
        assertEquals(55.70001, native.latitude!!, 0.000001)
    }

    @Test fun lossOfFusionDoesNotSilentlyDemoteNativeAppsToMultipathRaw() {
        FtPositionTelemetry.acceptFused(fused(10_080L), 10_080L)
        assertFalse(FtPositionTelemetry.acceptRaw(
            raw(12_000L, 55.7010, 3L), 12_000L))
        val stale = FtPositionTelemetry.current.value
        val native = CarGpsSourceSelector().selectFt(stale, null, 12_000L)
        assertEquals(CarGpsSourceSelector.Source.UNKNOWN, native.source)
        assertNull(native.latitude)
    }

    @Test fun invalidUnverifiedOrPhoneDrNeverClaimAuthoritativeFusion() {
        val bad = fused(10_100L).copy(
            fromSelectedGps = GpsSourceMode.PHONE, verifiedVehicleMotion = false)
        assertFalse(FtPositionTelemetry.acceptFused(bad, 10_100L))
        assertNull(FtPositionTelemetry.current.value)
        assertTrue(FtPositionTelemetry.acceptRaw(raw(10_110L), 10_110L))
        // Every actual received OEM event remains admissible without old/new checks.
    }

    @Test fun onlyExplicitResetReleasesFusedAuthorityToRawBaseline() {
        assertTrue(FtPositionTelemetry.acceptFused(fused(10_080L), 10_080L))
        assertFalse(FtPositionTelemetry.acceptRaw(raw(10_101L), 10_101L))
        FtPositionTelemetry.clear()
        assertNull(FtPositionTelemetry.current.value)
        assertTrue(FtPositionTelemetry.acceptRaw(raw(12_000L), 12_000L))
        assertEquals(FtNavigationPosition.Source.RAW_UNFILTERED,
            FtPositionTelemetry.current.value!!.source)
    }

    @Test fun manualPhoneSourceIsStillExplicitlySelected() {
        FtPositionTelemetry.acceptFused(fused(10_080L), 10_080L)
        val phone = CarInfo.PhoneGpsFix(
            latitude = 55.6, longitude = 37.5, speedKmh = 28f,
            bearingDegrees = 80f, receivedAtMs = 10_100L,
            provider = "gps", horizontalAccuracyMetres = 10f,
            fixAgeNanosAtReceipt = 0L)
        val normal = CarGpsSourceSelector().selectFt(
            FtPositionTelemetry.current.value, phone, 10_100L)
        assertEquals(CarGpsSourceSelector.Source.DR, normal.source)
        val manual = CarGpsSourceSelector().selectFt(
            FtPositionTelemetry.current.value, phone, 10_100L, manualPhoneMode = true)
        assertEquals(CarGpsSourceSelector.Source.PHONE, manual.source)
    }
}
