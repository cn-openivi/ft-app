package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class VehicleTelemetryTest {
    @Test fun reorderedGpsCannotOverwriteNewerSharedPositionOrGear() {
        val now = System.currentTimeMillis()
        val first = now - 2_000L
        val latest = now - 1_000L
        fun fix(t: Long, lat: Double) = VehicleLocation(
            source = "ICCOA", receivedAtMs = t, positionAtMs = t,
            latitude = lat, longitude = 37.6,
            coordinatesUnit = "ANDROID_LOCATION_WGS84"
        )
        try {
            VehicleTelemetry.clear("ICCOA")
            VehicleTelemetry.publish(fix(first, 55.1))
            VehicleTelemetry.publish(fix(latest, 55.2))
            // Arrival time is newer, but the measured position is older.
            VehicleTelemetry.publish(fix(first, 55.9).copy(receivedAtMs = now))
            assertEquals(latest, VehicleTelemetry.current.value!!.positionAtMs)
            assertEquals(55.2, VehicleTelemetry.current.value!!.latitude!!, 0.000001)

            VehicleTelemetry.updateIccoaSpeed(12L, gearRaw = 2L)
            VehicleTelemetry.publish(fix(first, 55.8).copy(receivedAtMs = now))
            val retained = VehicleTelemetry.current.value!!
            assertEquals(latest, retained.positionAtMs)
            assertEquals(55.2, retained.latitude!!, 0.000001)
            assertEquals(12L, retained.gearSpeedRaw)
            assertEquals(2L, retained.gearRaw)

            VehicleTelemetry.publish(fix(now, 55.3))
            val recovered = VehicleTelemetry.current.value!!
            assertEquals(now, recovered.positionAtMs)
            assertEquals(55.3, recovered.latitude!!, 0.000001)
            assertEquals(12L, recovered.gearSpeedRaw)
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
            assertFalse(result.positionFresh())
        } finally {
            VehicleTelemetry.clear("ICCOA")
        }
    }
}
