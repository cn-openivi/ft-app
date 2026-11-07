package app.ft.core

import java.util.ArrayDeque

/**
 * Diagnostic-only, arrival-time-aligned ICCOA trace for offline GPS/IMU calibration.
 * No polling, resampling, coordinate synthesis or changes to live vehicle state.
 * Called only while the owner has enabled vehicle coordinate recording.
 */
internal object VehicleTraceRecorder {
    private const val PERIOD_NS = 100_000_000L // At most 10 records/s per sensor kind.
    private const val CAPACITY = 50_000
    private const val GPS = 0
    private const val GEAR = 1
    private const val GYRO = 2
    private const val ACCEL = 3

    private val lastNs = LongArray(4) { Long.MIN_VALUE }
    private val rows = ArrayDeque<String>()
    private var evicted = 0L

    private const val HEADER =
        "phone_wall_ms,phone_elapsed_ns,source,event,oem_timestamp_raw," +
        "latitude,longitude,gps_speed_raw,gps_heading_raw,sat_used,sat_visible," +
        "gear_raw,gear_speed_raw,x,y,z,gyro_type"

    private fun eligible(kind: Int, elapsedNs: Long): Boolean {
        val previous = lastNs[kind]
        if (previous != Long.MIN_VALUE && elapsedNs >= previous &&
            elapsedNs - previous < PERIOD_NS) return false
        lastNs[kind] = elapsedNs
        return true
    }

    private fun add(
        wallMs: Long, elapsedNs: Long, event: String,
        oemTimestampRaw: Long? = null,
        latitude: Double? = null, longitude: Double? = null,
        gpsSpeedRaw: Long? = null, headingRaw: Long? = null,
        satUsed: Long? = null, satVisible: Long? = null,
        gearRaw: Long? = null, gearSpeedRaw: Long? = null,
        x: Double? = null, y: Double? = null, z: Double? = null,
        gyroType: Int? = null
    ) {
        val fields: Array<Any?> = arrayOf(
            wallMs, elapsedNs, "ICCOA", event, oemTimestampRaw,
            latitude, longitude, gpsSpeedRaw, headingRaw, satUsed, satVisible,
            gearRaw, gearSpeedRaw, x, y, z, gyroType
        )
        if (rows.size >= CAPACITY) {
            rows.removeFirst()
            evicted++
        }
        rows.addLast(fields.joinToString(",") { it?.toString().orEmpty() })
    }

    @Synchronized fun gps(
        wallMs: Long, elapsedNs: Long, oemTimestampRaw: Long,
        latitude: Double, longitude: Double, speedRaw: Long, headingRaw: Long,
        satUsed: Long, satVisible: Long
    ) {
        if (!eligible(GPS, elapsedNs)) return
        add(wallMs, elapsedNs, "GPS", oemTimestampRaw, latitude, longitude,
            speedRaw, headingRaw, satUsed, satVisible)
    }

    @Synchronized fun gear(
        wallMs: Long, elapsedNs: Long, gearRaw: Long?, speedRaw: Long?
    ) {
        if (!eligible(GEAR, elapsedNs)) return
        add(wallMs, elapsedNs, "GEAR", gearRaw = gearRaw, gearSpeedRaw = speedRaw)
    }

    @Synchronized fun imu(
        wallMs: Long, elapsedNs: Long, gyro: Boolean,
        oemTimestampRaw: Long?, x: Double, y: Double, z: Double,
        gyroType: Int?
    ) {
        if (!eligible(if (gyro) GYRO else ACCEL, elapsedNs)) return
        add(wallMs, elapsedNs, if (gyro) "GYRO" else "ACCEL",
            oemTimestampRaw = oemTimestampRaw, x = x, y = y, z = z, gyroType = gyroType)
    }

    @Synchronized fun exportCsv(): String = buildString {
        append(HEADER).append('\n')
        for (line in rows) append(line).append('\n')
    }

    @Synchronized fun exportInfo(): String =
        "format=ICCOA_DIAGNOSTIC_EVENT_TRACE_V1\n" +
        "max_hz_per_event=10\n" +
        "capacity_rows=$CAPACITY\n" +
        "retained_rows=${rows.size}\n" +
        "evicted_oldest_rows=$evicted\n" +
        "phone_wall_ms=Unix epoch milliseconds\n" +
        "phone_elapsed_ns=Android elapsedRealtimeNanos, arrival time\n" +
        "oem_timestamp_raw=OEM value, scale not established\n" +
        "gps_speed_raw=OEM floor(Location.getSpeed()), nominal m/s\n" +
        "gps_heading_raw=OEM floor(Location.getBearing()), nominal degrees\n" +
        "gear_speed_raw=OEM GearInfo value, units unverified\n" +
        "x_y_z=OEM raw axes, mapping and units unverified\n" +
        "no interpolation; rows reflect received events only\n"

    @Synchronized fun clear() {
        rows.clear()
        lastNs.fill(Long.MIN_VALUE)
        evicted = 0L
    }
}
