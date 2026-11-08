package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class VehicleTraceRecorderTest {
    @Test fun recordsActualEventsWithAlignedTimeAndRawUnitsAtMostTenHz() {
        VehicleTraceRecorder.clear()
        try {
            val t = 1_000_000_000L
            VehicleTraceRecorder.gps(1_000L, t, 901L, 55.75, 37.61, 5L, 123L, 8L, 10L)
            VehicleTraceRecorder.gear(1_000L, t, 2L, 17L)
            VehicleTraceRecorder.imu(1_000L, t, true, 902L, 1.25, -2.5, 0.75, 3)
            VehicleTraceRecorder.imu(1_000L, t, false, 903L, -0.2, 0.3, 9.8, null)
            // A second gyro event at +50ms must be suppressed, not the other streams.
            VehicleTraceRecorder.imu(1_050L, t + 50_000_000L, true, 904L, 99.0, 99.0, 99.0, 3)
            VehicleTraceRecorder.imu(1_100L, t + 100_000_000L, true, 905L, 1.5, 2.0, 3.0, 3)
            val lines = VehicleTraceRecorder.exportCsv().trimEnd().lines()
            assertEquals(6, lines.size)
            val heading = lines[0].split(",")
            assertEquals(17, heading.size)
            assertEquals("phone_wall_ms", heading[0])
            val records = lines.drop(1).map { it.split(",") }
            assertTrue(records.all { it.size == 17 })
            assertEquals(listOf("GPS", "GEAR", "GYRO", "ACCEL", "GYRO"),
                records.map { it[3] })
            assertEquals("55.75", records[0][5])
            assertEquals("37.61", records[0][6])
            assertEquals("5", records[0][7])
            assertEquals("123", records[0][8])
            assertEquals("17", records[1][12])
            assertEquals("-2.5", records[2][14])
            assertEquals("903", records[3][4])
            assertEquals("905", records[4][4])
            assertFalse(VehicleTraceRecorder.exportCsv().contains("99.0"))
            assertTrue(VehicleTraceRecorder.exportInfo().contains("max_hz_per_event=10"))
            assertTrue(VehicleTraceRecorder.exportInfo().contains("units unverified"))
        } finally {
            VehicleTraceRecorder.clear()
        }
    }

    @Test fun imuAndGearContinueAfterGpsStopsWithoutFabricatingGpsCoordinates() {
        VehicleTraceRecorder.clear()
        try {
            val t = 2_000_000_000L
            VehicleTraceRecorder.gps(2_000L, t, 10L, 55.5, 37.5, 2L, 270L, 6L, 9L)
            VehicleTraceRecorder.gear(85_000L, t + 83_000_000_000L, 3L, 1L)
            VehicleTraceRecorder.imu(85_000L, t + 83_000_000_000L,
                true, 42L, 0.01, -0.02, 0.03, null)
            val csv = VehicleTraceRecorder.exportCsv()
            assertEquals(1, csv.lines().count { it.contains(",GPS,") })
            assertEquals(1, csv.lines().count { it.contains(",GEAR,") })
            assertEquals(1, csv.lines().count { it.contains(",GYRO,") })
            VehicleTraceRecorder.clear()
            assertEquals(1, VehicleTraceRecorder.exportCsv().trimEnd().lines().size)
        } finally {
            VehicleTraceRecorder.clear()
        }
    }

    @Test fun boundsReportToLatestFiftyThousandRecords() {
        VehicleTraceRecorder.clear()
        try {
            val start = 1_000_000_000L
            for (i in 0..50_005) {
                VehicleTraceRecorder.imu(i.toLong(), start + i * 100_000_000L,
                    true, i.toLong(), 1.0, 2.0, 3.0, null)
            }
            val info = VehicleTraceRecorder.exportInfo()
            assertTrue(info.contains("capacity_rows=50000"))
            assertTrue(info.contains("retained_rows=50000"))
            assertTrue(info.contains("evicted_oldest_rows=6"))
            val lines = VehicleTraceRecorder.exportCsv().lines()
            assertFalse(lines[1].contains(",0,ICCOA,"))
            assertTrue(lines.any { it.startsWith("50005,") })
        } finally {
            VehicleTraceRecorder.clear()
        }
    }
}
