package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class VehicleTelemetryTest {
    @Test fun zeroTimestampCannotReplaceAcceptedPosition() {
        val now = System.currentTimeMillis()
        fun fix(position: Long, lat: Double) = VehicleLocation(
            source = "ICCOA", receivedAtMs = now, positionAtMs = position,
            latitude = lat, longitude = 37.6,
            coordinatesUnit = "ANDROID_LOCATION_WGS84"
        )
        try {
            VehicleTelemetry.clear("ICCOA")
            VehicleTelemetry.publish(fix(now - 1_000L, 55.1))
            VehicleTelemetry.updateIccoaSpeed(12L, gearRaw = 2L)
            VehicleTelemetry.publish(fix(0L, 55.9))
            val retained = VehicleTelemetry.current.value!!
            assertEquals(now - 1_000L, retained.positionAtMs)
            assertEquals(55.1, retained.latitude!!, 0.000001)
            assertEquals(12L, retained.gearSpeedRaw)
            assertEquals(2L, retained.gearRaw)
            VehicleTelemetry.publish(fix(now, 55.2))
            assertEquals(now, VehicleTelemetry.current.value!!.positionAtMs)
            assertEquals(55.2, VehicleTelemetry.current.value!!.latitude!!, 0.000001)
        } finally {
            VehicleTelemetry.clear("ICCOA")
        }
    }

    @Test fun rejectedFuturePositionCannotBlockLaterValidCarFix() {
        val now = System.currentTimeMillis()
        fun fix(position: Long, received: Long, latitude: Double) = VehicleLocation(
            source = "ICCOA", receivedAtMs = received, positionAtMs = position,
            latitude = latitude, longitude = 37.6,
            coordinatesUnit = "ANDROID_LOCATION_WGS84"
        )
        try {
            VehicleTelemetry.clear("ICCOA")
            VehicleTelemetry.publish(fix(now - 1_000L, now, 55.1))
            // This would previously poison the high-water mark even though
            // CarGpsSourceSelector rejects fixes more than 1s in the future.
            VehicleTelemetry.publish(fix(now + 1_001L, now, 55.9))
            assertEquals(now - 1_000L, VehicleTelemetry.current.value!!.positionAtMs)
            VehicleTelemetry.publish(fix(now, now, 55.2))
            assertEquals(now, VehicleTelemetry.current.value!!.positionAtMs)
            assertEquals(55.2, VehicleTelemetry.current.value!!.latitude!!, 0.000001)
            // The same 1s tolerance as the selector remains accepted.
            VehicleTelemetry.publish(fix(now + 1_000L, now, 55.3))
            assertEquals(now + 1_000L, VehicleTelemetry.current.value!!.positionAtMs)
        } finally {
            VehicleTelemetry.clear("ICCOA")
        }
    }

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
