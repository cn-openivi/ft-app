package app.ft.iccoa

/**
 * Read-only provenance diagnostic for the OEM CarLink LocationManager GPS route.
 * Monotonic *receipt* timestamps are NOT GNSS acquisition timestamps.
 * Never turns a gap into a fabricated GNSS-loss flag or synthetic DR location.
 *
 * This tells an ordinary GPS acquisition delay apart from a gap after the
 * first received GPS message. GearInfo may be event-driven, so a positive
 * raw speed is treated only as an OEM motion indication, not verified wheel
 * odometry. No absolute coordinates are stored or logged.
 */
internal object IccoaDrSourceGapEvidence {
    data class Observation(
        val kind: String,
        val elapsedMs: Long,
        val rawMovingGearFrames: Int
    )
    data class Snapshot(
        val firstGearToFirstGpsMs: Long?,
        val firstMovingGearToFirstGpsMs: Long?,
        val maxInterGpsReceiptGapMs: Long,
        val movingGearFramesBeforeFirstGps: Long,
        val movingGearFramesDuringGap: Long,
        val gpsFrames: Long,
        val gearFrames: Long
    ) {
        fun reportLine(): String = listOf(
            "format=ICCOA_DR_SOURCE_GAPS_V1",
            "firstGearToFirstGpsReceiptMs=${firstGearToFirstGpsMs ?: "UNKNOWN"}",
            "firstMovingGearToFirstGpsReceiptMs=${firstMovingGearToFirstGpsMs ?: "UNKNOWN"}",
            "maxInterGpsReceiptGapMs=$maxInterGpsReceiptGapMs",
            "movingGearFramesBeforeFirstGps=$movingGearFramesBeforeFirstGps",
            "movingGearFramesDuringInterGpsGaps=$movingGearFramesDuringGap",
            "gpsFrames=$gpsFrames gearFrames=$gearFrames",
            "oemGpsReceivedClock=PHONE_MONOTONIC_RECEIPT_NOT_FIX_CLOCK",
            "oemGearSpeedUnit=UNVERIFIED",
            "gnssActuallyLost=UNKNOWN",
            "oemDeadReckoningProven=NO",
            "ftVerifiedEkfInputs=NOT_PRODUCED_BY_THIS_DIAGNOSTIC"
        ).joinToString("\n", postfix = "\n")
    }

    private var firstGearAt = 0L
    private var firstMovingGearAt = 0L
    private var firstGapMs: Long? = null
    private var firstMovingGapMs: Long? = null
    private var previousGpsAt = 0L
    private var maxInterGpsMs = 0L
    private var movingGearBeforeGps = 0L
    private var movingGearInGap = 0L
    private var gpsCount = 0L
    private var gearCount = 0L

    @Synchronized fun beginSession() {
        firstGearAt = 0L; firstMovingGearAt = 0L
        firstGapMs = null; firstMovingGapMs = null
        previousGpsAt = 0L; maxInterGpsMs = 0L
        movingGearBeforeGps = 0L; movingGearInGap = 0L
        gpsCount = 0L; gearCount = 0L
    }

    @Synchronized fun onGear(speedRaw: Long?, receiptMonoMs: Long) {
        if (receiptMonoMs <= 0L) return
        gearCount++
        if (firstGearAt == 0L) firstGearAt = receiptMonoMs
        // This is NOT independent wheel-speed qualification.
        if (speedRaw != null && speedRaw > 0L) {
            if (firstMovingGearAt == 0L) firstMovingGearAt = receiptMonoMs
            if (previousGpsAt == 0L) movingGearBeforeGps++
            else movingGearInGap++
        }
    }

    /** Returns only a diagnostic observation after a GPS receipt; never an EKF event. */
    @Synchronized fun onGps(receiptMonoMs: Long): Observation? {
        if (receiptMonoMs <= 0L) return null
        gpsCount++
        val before = previousGpsAt
        val gap = if (before != 0L && receiptMonoMs >= before)
            receiptMonoMs - before else 0L
        maxInterGpsMs = maxOf(maxInterGpsMs, gap)
        val observation = if (before == 0L) {
            firstGapMs = firstGearAt.takeIf { it in 1..receiptMonoMs }
                ?.let { receiptMonoMs - it }
            firstMovingGapMs = firstMovingGearAt.takeIf { it in 1..receiptMonoMs }
                ?.let { receiptMonoMs - it }
            firstMovingGapMs?.takeIf { it >= 10_000L && movingGearBeforeGps > 0L }
                ?.let { Observation("FIRST_GPS_AFTER_MOVING_GEAR", it,
                    movingGearBeforeGps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        } else if (gap >= 10_000L && movingGearInGap > 0L) {
            Observation("INTER_GPS_RECEIPT_GAP_WITH_GEAR", gap,
                movingGearInGap.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        } else null
        movingGearInGap = 0L
        previousGpsAt = receiptMonoMs
        return observation
    }

    @Synchronized fun snapshot() = Snapshot(
        firstGapMs, firstMovingGapMs, maxInterGpsMs,
        movingGearBeforeGps, movingGearInGap, gpsCount, gearCount)
}
