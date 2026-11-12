package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class VehicleCoordinateLogTest {
    private val sample = VehicleLocation(
        source = "ICCOA",
        receivedAtMs = 13_000L,
        latitude = 55.755826,
        longitude = 37.617300,
        rawLatitude = 55755826,
        rawLongitude = 37617300,
        speedRaw = 34L
    )
    private val decision = VehicleLogSampler.Decision("FIX_REACQUIRED", true)

    @Test fun defaultOffIncludesHealthButNotCoordinates() {
        val log = VehicleCoordinateLog.format(sample, decision, false)
        assertTrue(log.contains("coordinates=OMITTED"))
        assertTrue(log.contains("reason=FIX_REACQUIRED"))
        assertTrue(log.contains("mode=EVENT_1HZ"))
        assertFalse(log.contains("55.755826"))
        assertFalse(log.contains("37.617300"))
        assertFalse(log.contains("55755826"))
        assertFalse(log.contains("37617300"))
        assertFalse(log.contains("latitude="))
        assertFalse(log.contains("rawLat="))
    }

    @Test fun gearDiagnosticsSeparateRawGearFromGpsSpeed() {
        val record = sample.copy(gearRaw = 2, gearSpeedRaw = 60, gearAtMs = 13_000L)
        val output = VehicleCoordinateLog.format(record, decision, false)
        assertTrue(output.contains("gpsSpeedRaw=34"))
        assertTrue(output.contains("gearSpeedRaw=60"))
        assertTrue(output.contains("gearRaw=2"))
        assertTrue(output.contains("gearState=DRIVE"))
        assertTrue(output.contains("coordinates=OMITTED"))
    }

    @Test fun independentlySampledPhoneSpeedIsNotConflatedWithOemRawUnits() {
        val record = sample.copy(gearRaw = 2, gearSpeedRaw = 256L, speedRaw = 78L)
        val comparison = VehicleCoordinateLog.format(record, decision, false, phoneGpsSpeedKmh = 61.4f)
        assertTrue(comparison.contains("gpsSpeedRaw=78 gearSpeedRaw=256"))
        assertTrue(comparison.contains("phoneGpsSpeedKmh=61.4"))
        assertTrue(comparison.contains("coordinates=OMITTED"))
        assertFalse(comparison.contains("latitude="))
        // A diagnostics-only independent observation must never assign a
        // speed multiplier to the shared live vehicle telemetry.
        assertEquals("UNKNOWN", record.speedUnit)
        assertNull(record.speedKmh)
    }

    @Test fun unavailableOrInvalidPhoneSpeedIsClearlyMarkedUnknown() {
        val normal = VehicleCoordinateLog.format(sample, decision, false)
        assertTrue(normal.contains("phoneGpsSpeedKmh=NA"))
        val invalid = VehicleCoordinateLog.format(sample, decision, false, phoneGpsSpeedKmh = Float.NaN)
        assertTrue(invalid.contains("phoneGpsSpeedKmh=NA"))
        val implausible = VehicleCoordinateLog.format(sample, decision, false, phoneGpsSpeedKmh = 400f)
        assertTrue(implausible.contains("phoneGpsSpeedKmh=NA"))
    }

    @Test fun ownerOptInIncludesExactCoordinates() {
        val log = VehicleCoordinateLog.format(sample, decision, true)
        assertTrue(log.contains("latitude=55.7558260"))
        assertTrue(log.contains("longitude=37.6173000"))
        assertTrue(log.contains("rawLat=55755826"))
        assertTrue(log.contains("rawLon=37617300"))
    }

    @Test fun oldVehicleGpsLinesAreRedactedAtExport() {
        val old = "11:11 I VehicleGPS: latitude=55.7558260 longitude=37.6173000 rawLat=55755826 rawLon=37617300 speedRaw=10"
        val redacted = VehicleCoordinateLog.scrubStoredLine(old)
        assertFalse(redacted.contains("55.755826"))
        assertFalse(redacted.contains("37.6173"))
        assertFalse(redacted.contains("55755826"))
        assertTrue(redacted.contains("speedRaw=10"))
        assertEquals("OTHER: latitude=55.7558260", VehicleCoordinateLog.scrubStoredLine("OTHER: latitude=55.7558260"))
    }

    @Test fun previousIccoaTouchCoordinatesAreRedactedDuringZipExport() {
        val old = "11:11 I ICCOA: UIBC touch action=0 x=472 y=635 raw=253.7,522.2 dst=1920x1080"
        val safe = VehicleCoordinateLog.scrubStoredLine(old)
        assertFalse(safe.contains("x=472"))
        assertFalse(safe.contains("y=635"))
        assertFalse(safe.contains("253.7,522.2"))
        assertTrue(safe.contains("dst=1920x1080"))
        val mirror = VehicleCoordinateLog.scrubStoredLine(
            "11:11 I Mirror: touch DOWN car=500,250 phone=1024.5,700.2 source=1080x2340"
        )
        assertFalse(mirror.contains("500,250"))
        assertFalse(mirror.contains("1024.5,700.2"))
        assertTrue(mirror.contains("source=1080x2340"))
        // No generic numeric redaction of unrelated records.
        assertEquals("Other: x=472 y=635", VehicleCoordinateLog.scrubStoredLine("Other: x=472 y=635"))
    }

    @Test fun opaqueProtocolPayloadCannotLeakFromOldLogs() {
        val old = "11:11 D CarLife: RX CMD_CAR_GPS  0A FF 23 4C"
        assertEquals("11:11 D CarLife: RX payload=<redacted>", VehicleCoordinateLog.scrubStoredLine(old))
        val outbound = "11:11 D AA: TX SENSOR_EVENT  09 00 01"
        assertEquals("11:11 D AA: TX payload=<redacted>", VehicleCoordinateLog.scrubStoredLine(outbound))
    }
}
