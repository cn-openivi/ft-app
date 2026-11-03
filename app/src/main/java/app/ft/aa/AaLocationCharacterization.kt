package app.ft.aa

/**
 * LocationCharacterization bits documented by AOSP Automotive VehicleProperty.
 * AA SensorChannelConfig field 2 carries this bitmask in FT's current SDP.
 * The selector is frozen for an AA session: do not advertise fusion merely
 * because the app contains a TinyEKF class with no verified OEM inputs.
 */
internal object AaLocationCharacterization {
    const val PRIOR_LOCATIONS = 0x01
    const val GYROSCOPE_FUSION = 0x02
    const val ACCELEROMETER_FUSION = 0x04
    const val WHEEL_SPEED_FUSION = 0x10
    const val DEAD_RECKONED = 0x80
    const val RAW_GNSS_ONLY = 0x100

    const val VERIFIED_WHEEL_GYRO_DR =
        PRIOR_LOCATIONS or GYROSCOPE_FUSION or WHEEL_SPEED_FUSION or DEAD_RECKONED

    fun forSession(hasVerifiedLiveDr: Boolean): Int =
        if (hasVerifiedLiveDr) VERIFIED_WHEEL_GYRO_DR else RAW_GNSS_ONLY

    fun permitsEstimatedPosition(characterization: Int): Boolean =
        characterization and DEAD_RECKONED != 0 &&
            characterization and RAW_GNSS_ONLY == 0
}
