package app.ft.dr

/**
 * Passive route proof. Raw ICCOA gear speed and gyro axes are not verified
 * wheel metres/second or yaw radians/second and MUST NOT be fed to the EKF.
 * No new OEM subscriptions, synthetic positions or AA wire messages.
 *
 * Keep the last ended session until the next connection, since users often
 * export their diagnostic ZIP after disconnecting from the vehicle.
 */
internal data class DrPathSnapshot(
    val session: Long,
    val active: Boolean,
    val rawGps: Long,
    val rawGear: Long,
    val rawGyro: Long,
    val rawAcceleration: Long,
    val lastRawGpsReceivedAtMs: Long,
    val verifiedGnssAdmitted: Long,
    val verifiedGnssRejected: Long,
    val verifiedMotionSubmitted: Long,
    val unverifiedMotionRejected: Long,
    val ekfPublished: Long,
    val ftFusedPublished: Long,
    val aaRawWritten: Long,
    val aaFusedWritten: Long
) {
    val stage: String get() = when {
        aaFusedWritten > 0 -> "AA_VERIFIED_FUSED_SOCKET_WRITTEN"
        ftFusedPublished > 0 -> "FT_FUSED_AVAILABLE_AA_NOT_CONFIRMED"
        ekfPublished > 0 -> "EKF_PUBLISHED_FT_NOT_CONFIRMED"
        rawGps == 0L -> "NO_OEM_GPS_EVENTS"
        rawGear == 0L -> "NO_OEM_GEAR_EVENTS"
        rawGyro == 0L -> "NO_OEM_GYRO_EVENTS"
        verifiedGnssAdmitted == 0L -> "RAW_GNSS_WITHOUT_VERIFIED_ACCURACY_FIX_TIME"
        verifiedMotionSubmitted == 0L -> "RAW_SPEED_GYRO_WITHOUT_VERIFIED_WHEEL_YAW_UNITS"
        else -> "VERIFIED_INPUTS_NO_EKF_PREDICTION"
    }

    /** Packet receipt age is evidence only, not a fixed GNSS-loss threshold. */
    fun reportLine(nowMs: Long): String {
        val age = if (lastRawGpsReceivedAtMs > 0 && nowMs >= lastRawGpsReceivedAtMs)
            (nowMs - lastRawGpsReceivedAtMs).toString() else "UNKNOWN"
        return listOf(
            "format=FT_DR_PATH_EVIDENCE_V1",
            "session=$session active=$active",
            "rawOemGps=$rawGps rawGear=$rawGear rawGyro=$rawGyro rawAcceleration=$rawAcceleration",
            "lastRawGpsReceiptAgeMs=$age (NOT_GNSS_ACQUISITION_AGE)",
            "verifiedGnssAdmitted=$verifiedGnssAdmitted rejected=$verifiedGnssRejected",
            "verifiedMotionSubmitted=$verifiedMotionSubmitted unverifiedMotionRejected=$unverifiedMotionRejected",
            "ekfPublished=$ekfPublished ftFusedPublished=$ftFusedPublished",
            "aaRawSocketWrites=$aaRawWritten aaFusedSocketWrites=$aaFusedWritten",
            "stage=$stage",
            "gearSpeedUnit=UNVERIFIED gyroYawAxisAndUnit=UNVERIFIED",
            "gnssHorizontalAccuracyAndAcquisitionClock=UNVERIFIED",
            "phoneNavigationConsumedLocation=UNKNOWN",
            "RAW_GPS_IS_NOT_DR=true"
        ).joinToString("\n", postfix = "\n")
    }
}

internal object DrPathEvidence {
    private var session = 0L
    private var active = false
    private var rawGps = 0L
    private var rawGear = 0L
    private var rawGyro = 0L
    private var rawAcceleration = 0L
    private var lastGpsMs = 0L
    private var verifiedGnssAdmitted = 0L
    private var verifiedGnssRejected = 0L
    private var verifiedMotionSubmitted = 0L
    private var unverifiedMotionRejected = 0L
    private var ekfPublished = 0L
    private var ftFusedPublished = 0L
    private var aaRawWritten = 0L
    private var aaFusedWritten = 0L

    @Synchronized fun beginSession() {
        session++
        active = true
        rawGps = 0L
        rawGear = 0L
        rawGyro = 0L
        rawAcceleration = 0L
        lastGpsMs = 0L
        verifiedGnssAdmitted = 0L
        verifiedGnssRejected = 0L
        verifiedMotionSubmitted = 0L
        unverifiedMotionRejected = 0L
        ekfPublished = 0L
        ftFusedPublished = 0L
        aaRawWritten = 0L
        aaFusedWritten = 0L
    }
    @Synchronized fun endSession() { active = false }
    @Synchronized fun rawGps(atMs: Long) {
        rawGps++
        if (atMs > 0L) lastGpsMs = atMs
    }
    @Synchronized fun rawGear() { rawGear++ }
    @Synchronized fun rawGyro() { rawGyro++ }
    @Synchronized fun rawAcceleration() { rawAcceleration++ }
    @Synchronized fun verifiedGnssSubmitted(accepted: Boolean) {
        if (accepted) verifiedGnssAdmitted++ else verifiedGnssRejected++
    }
    @Synchronized fun verifiedMotionSubmitted(verified: Boolean) {
        if (verified) verifiedMotionSubmitted++ else unverifiedMotionRejected++
    }
    @Synchronized fun ekfPublished() { ekfPublished++ }
    @Synchronized fun ftFusedPublished() { ftFusedPublished++ }
    @Synchronized fun aaLocationWritten(fused: Boolean) {
        if (fused) aaFusedWritten++ else aaRawWritten++
    }
    @Synchronized fun snapshot() = DrPathSnapshot(
        session, active, rawGps, rawGear, rawGyro, rawAcceleration,
        lastGpsMs, verifiedGnssAdmitted, verifiedGnssRejected,
        verifiedMotionSubmitted, unverifiedMotionRejected,
        ekfPublished, ftFusedPublished, aaRawWritten, aaFusedWritten
    )
}
