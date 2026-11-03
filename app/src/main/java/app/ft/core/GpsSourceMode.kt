package app.ft.core

/**
 * User-selected, persistent GPS origin; no automatic vehicle/handset fallback.
 *
 * CAR: only the head unit's verified vehicle position is eligible for FT
 * widgets and the future wheel/IMU DR pipeline. Phone coordinates are NEVER
 * treated as a correction or substitute for a lost car fix.
 *
 * PHONE: only Android handset GPS is used for FT navigation widgets; FT does
 * not offer OEM LOCATION to Android Auto and does not feed the phone fix into
 * the vehicle DR estimator.
 *
 * This is independent of CarProtocol transport. Existing Baidu installs
 * default to PHONE until the user explicitly changes the source because
 * Baidu OEM coordinate scale/CRS is not field-verified.
 */
enum class GpsSourceMode(val id: String) {
    CAR("car"),
    PHONE("phone");

    companion object {
        fun fromId(id: String): GpsSourceMode =
            entries.firstOrNull { it.id == id } ?: CAR

        fun defaultFor(protocol: CarProtocol): GpsSourceMode =
            if (protocol == CarProtocol.ICCOA) CAR else PHONE
    }

    fun advertisesCarLocation(protocol: CarProtocol): Boolean =
        this == CAR && protocol == CarProtocol.ICCOA

    fun permitsVehicleDr(): Boolean = this == CAR
}
