package app.ft.aa

import app.ft.core.ProtoWriter
import app.ft.core.VehicleLocation
import app.ft.iccoa.IccoaGearState

/**
 * Translate only an OEM-verified, fresh GearInfo enum to the AA gear schema.
 * No speed-unit conversion. In normal mode, the actual gear must be explicitly
 * supplied by the car. The isolated, opt-in forcePark experiment intentionally
 * overrides only the Android Auto GEAR payload; it never modifies OEM telemetry.
 */
internal object AaCarGear {
    const val SENSOR_TYPE = 8
    private const val MAX_AGE_MS = 3_000L

    /** Android Auto Gear enum, independent of Toyota's GearState. */
    fun aaGear(location: VehicleLocation?, nowMs: Long, forcePark: Boolean = false): Int? {
        if (forcePark) return 101
        if (location?.source != "ICCOA" || location.gearAtMs <= 0L ||
            nowMs < location.gearAtMs || nowMs - location.gearAtMs > MAX_AGE_MS) return null
        return when (IccoaGearState.fromRaw(location.gearRaw)) {
            IccoaGearState.PARK -> 101
            IccoaGearState.DRIVE -> 100
            IccoaGearState.REVERSE -> 102
            IccoaGearState.NEUTRAL -> 0
            IccoaGearState.UNKNOWN -> null
        }
    }

    fun event(location: VehicleLocation?, nowMs: Long, forcePark: Boolean = false): ByteArray? {
        val gear = aaGear(location, nowMs, forcePark) ?: return null
        // SensorEventIndication.gear_data is repeated field 8;
        // GearData.gear is enum field 1.
        return ProtoWriter().message(SENSOR_TYPE,
            ProtoWriter().enum(1, gear)).toByteArray()
    }

    fun sensorError(status: Int): ByteArray =
        ProtoWriter().enum(1, SENSOR_TYPE).enum(2, status).toByteArray()
}
