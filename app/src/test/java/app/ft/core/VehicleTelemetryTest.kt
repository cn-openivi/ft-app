package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class VehicleTelemetryTest {
    @Test fun iccoaSpeedOnlyPacketDoesNotRefreshGearOrGpsClock() {
        val now = System.currentTimeMillis()
        val earlier = now - 6_000L
        try {
            VehicleTelemetry.clear("ICCOA")
            VehicleTelemetry.publish(VehicleLocation(
                source = "ICCOA", receivedAtMs = earlier,
                positionAtMs = earlier, latitude = 55.7, longitude = 37.6,
                gearSpeedRaw = 10L, gearSpeedAtMs = earlier,
                gearRaw = 2L, gearAtMs = earlier,
                coordinatesUnit = "ANDROID_LOCATION_WGS84"
            ))
            // GearInfo method 6 can contain uint32 speed but no gear enum.
            VehicleTelemetry.updateIccoaSpeed(25L, gearRaw = null)
            val updated = VehicleTelemetry.current.value!!
            assertEquals(25L, updated.gearSpeedRaw)
            assertTrue(updated.gearSpeedFresh())
            assertEquals(earlier, updated.gearAtMs)
            assertEquals(2L, updated.gearRaw)
            assertEquals(earlier, updated.positionAtMs)
            // Historical position is still recorded; this predicate does NOT
            // declare the GNSS signal live or trigger a loss timer.
            assertTrue(updated.positionFresh())
            assertFalse(updated.copy(gearSpeedAtMs = earlier).gearSpeedFresh(now))
            // A later genuine GNSS fix may carry forward the *separate*
            // speed clock, but must not make the old transmission gear fresh.
            VehicleTelemetry.publish(VehicleLocation(
                source = "ICCOA", receivedAtMs = System.currentTimeMillis(),
                positionAtMs = System.currentTimeMillis(),
                latitude = 55.701, longitude = 37.601,
                coordinatesUnit = "ANDROID_LOCATION_WGS84"
            ))
            val merged = VehicleTelemetry.current.value!!
            assertTrue(merged.gearSpeedFresh())
            assertEquals(earlier, merged.gearAtMs)
            assertEquals(25L, merged.gearSpeedRaw)
        } finally {
            VehicleTelemetry.clear("ICCOA")
        }
    }

    @Test fun allOemCarlinkGpsMetadataSurvivesVehicleTelemetryUpdates() {
        val now = System.currentTimeMillis()
        try {
            VehicleTelemetry.clear("ICCOA")
            VehicleTelemetry.publish(VehicleLocation(
                source = "ICCOA", receivedAtMs = now, positionAtMs = now,
                latitude = 55.7, longitude = 37.6, coordinatesUnit = "ANDROID_LOCATION_WGS84",
                altitudeMeters = 112.5, altitudeUnit = "ANDROID_LOCATION_METRES",
                antennaStateRaw = 3L, pdopRaw = 7L, satellitesUsed = 12L,
                satellitesVisible = 16L, deviceTimestampRaw = now,
                speedRaw = 3L, headingRaw = 90L
            ))
            VehicleTelemetry.updateIccoaSpeed(15L, gearRaw = 2L)
            val fix = VehicleTelemetry.current.value!!
            assertEquals(112.5, fix.altitudeMeters!!, 0.001)
            assertEquals("ANDROID_LOCATION_METRES", fix.altitudeUnit)
            assertEquals(3L, fix.antennaStateRaw)
            assertEquals(7L, fix.pdopRaw)
            assertEquals(12L, fix.satellitesUsed)
            assertEquals(16L, fix.satellitesVisible)
            assertEquals(now, fix.deviceTimestampRaw)
            assertEquals(3L, fix.speedRaw)
            assertEquals(90L, fix.headingRaw)
            assertEquals(15L, fix.gearSpeedRaw)
        } finally {
            VehicleTelemetry.clear("ICCOA")
        }
    }

    @Test fun gearFramesRetainLastKnownGpsButDoNotMakeItFresh() {
        val now = System.currentTimeMillis()
        val stalePosition = now - 10_000L
        VehicleTelemetry.publish(VehicleLocation(
            source = "ICCOA",
            receivedAtMs = stalePosition,
            positionAtMs = stalePosition,
            latitude = 55.7, longitude = 37.6,
            headingRaw = 120, headingDegrees = 120f,
            coordinatesUnit = "ANDROID_LOCATION_WGS84"
        ))
        try {
            VehicleTelemetry.updateIccoaSpeed(6L, gearRaw = 2L)
            val result = VehicleTelemetry.current.value!!
            assertEquals(55.7, result.latitude!!, 0.000001)
            assertEquals(37.6, result.longitude!!, 0.000001)
            assertEquals(120f, result.headingDegrees!!, 0f)
            assertEquals(stalePosition, result.positionAtMs)
            assertEquals(6L, result.gearSpeedRaw)
            assertEquals(2L, result.gearRaw)
            assertTrue(result.fresh())
            // A stored OEM position is not automatically replayed, and is
            // distinguished from a new GNSS event by gpsPacketSequence.
            assertTrue(result.positionFresh())
            assertEquals(stalePosition, result.positionAtMs)
        } finally {
            VehicleTelemetry.clear("ICCOA")
        }
    }
}
