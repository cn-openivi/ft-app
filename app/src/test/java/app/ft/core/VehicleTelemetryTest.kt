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

    @Test fun duplicateOemPositionTimestampCannotReplaceCoordinatesOrGear() {
        val now = System.currentTimeMillis()
        fun fix(position: Long, latitude: Double) = VehicleLocation(
            source = "ICCOA", receivedAtMs = now, positionAtMs = position,
            latitude = latitude, longitude = 37.6,
            coordinatesUnit = "ANDROID_LOCATION_WGS84"
        )
        try {
            VehicleTelemetry.clear("ICCOA")
            VehicleTelemetry.publish(fix(now - 1_000L, 55.1))
            VehicleTelemetry.updateIccoaSpeed(12L, gearRaw = 2L)
            // Equal measurement timestamp is not a new OEM fix, even when
            // its coordinates disagree with the previously accepted sample.
            VehicleTelemetry.publish(fix(now - 1_000L, 55.9))
            val retained = VehicleTelemetry.current.value!!
            assertEquals(now - 1_000L, retained.positionAtMs)
            assertEquals(55.1, retained.latitude!!, 0.000001)
            assertEquals(12L, retained.gearSpeedRaw)
            assertEquals(2L, retained.gearRaw)
            VehicleTelemetry.publish(fix(now, 55.2))
            val accepted = VehicleTelemetry.current.value!!
            assertEquals(now, accepted.positionAtMs)
            assertEquals(55.2, accepted.latitude!!, 0.000001)
            assertEquals(12L, accepted.gearSpeedRaw)
            assertEquals(2L, accepted.gearRaw)
        } finally {
            VehicleTelemetry.clear("ICCOA")
        }
    }

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
            assertFalse(updated.positionFresh())
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

    @Test fun onlyIncomingIccoaGpsFramesIncrementReceiveSequence() {
        val now = System.currentTimeMillis()
        try {
            VehicleTelemetry.clear("ICCOA")
            fun fix(stamp: Long) = VehicleLocation(
                source = "ICCOA", receivedAtMs = now, positionAtMs = now,
                latitude = 55.7, longitude = 37.6,
                coordinatesUnit = "ANDROID_LOCATION_WGS84", deviceTimestampRaw = stamp)
            VehicleTelemetry.publish(fix(now))
            val n = VehicleTelemetry.current.value!!.gpsPacketSequence
            assertTrue(n > 0L)
            VehicleTelemetry.updateIccoaSpeed(3L)
            assertEquals(n, VehicleTelemetry.current.value!!.gpsPacketSequence)
            VehicleTelemetry.publish(fix(now)) // same packet is not a new GPS sample
            assertEquals(n, VehicleTelemetry.current.value!!.gpsPacketSequence)
            VehicleTelemetry.publish(fix(now + 1L))
            assertEquals(n + 1L, VehicleTelemetry.current.value!!.gpsPacketSequence)
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
