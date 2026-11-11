package app.ft.core

/**
 * GNSS provenance gate for future TinyEKF corrections and Android Auto SDP.
 *
 * The FT-native dashboard and all FT apps take the central FT position
 * telemetry stream. There is NO car-to-phone automatic hysteresis/timer.
 * A genuine OEM GNSS observation alone does not override verified fused DR.
 *
 * CAR: accept only verified OEM GNSS to correct TinyEKF and advertise car
 * LOCATION in ICCOA. AA's phone chooses whether to subscribe to LOCATION and
 * may independently use phone location after a sensor error; FT NEVER switches
 * to PHONE automatically. No unsolicited AA events.
 * PHONE: explicitly user-selected mode for a head unit without car GPS.
 * Do not advertise OEM LOCATION or silently promote phone network/approximate
 * fixes to a verified vehicle DR reference. Both modes may predict with verified wheel
 * and yaw data; phone IMU is never silently substituted.
 *
 * Baidu defaults to PHONE until its OEM coordinate scale and CRS are verified.
 * Existing stored values remain compatible; no profile or migration change.
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
