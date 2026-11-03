package app.ft.core

/**
 * User-selected, persistent GPS origin; no automatic vehicle/handset fallback.
 *
 * The setting controls only the ABSOLUTE GNSS reference and LOCATION origin,
 * not the car's independent wheel/IMU odometry.
 *
 * CAR: accept only OEM GNSS as TinyEKF measurement; PHONE is never a fallback.
 * PHONE: accept only handset GNSS as TinyEKF measurement; do not offer OEM
 * LOCATION to AA. In BOTH modes the same verified OEM wheel-speed + yaw
 * may power DR prediction if available. Phone accelerometer, gyro and
 * magnetometer are deliberately not mixed into that estimate: handset axis
 * mounting and magnetic interference are unverified.
 *
 * When no verified OEM speed/yaw is available, we cannot claim standalone
 * FT DR merely from handset GPS or smartphone inertial sensors.
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

    /** Neither mode authorizes using GPS fixes from the other source. */
    fun acceptsGnssFrom(origin: GpsSourceMode): Boolean = this == origin

    /** DR may predict in either mode, using VERIFIED car odometry only. */
    fun permitsVerifiedCarOdometry(): Boolean = true
}
