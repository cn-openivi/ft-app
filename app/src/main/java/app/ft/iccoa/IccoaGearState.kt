package app.ft.iccoa

/**
 * Exact values from the owner FAW Toyota HU Carlink.apk / ucar.proto:
 * GearInfo.GearState. These are NOT the Android Auto gear enum values.
 */
internal enum class IccoaGearState {
    UNKNOWN, PARK, DRIVE, REVERSE, NEUTRAL;

    companion object {
        fun fromRaw(raw: Long?): IccoaGearState = when (raw) {
            1L -> PARK
            2L -> DRIVE
            3L -> REVERSE
            4L -> NEUTRAL
            else -> UNKNOWN
        }
    }
}
