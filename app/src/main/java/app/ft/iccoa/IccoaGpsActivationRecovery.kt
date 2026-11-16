package app.ft.iccoa

/**
 * One-shot, event-driven recovery attempt for the OEM Carlink GPS producer.
 *
 * FAW Carlink's CONTROL11 notifyNavigationInfo (field 1 isNavigating=true)
 * is already used for authenticated sensor activation. GearInfo continuing
 * while GPS packets stop is evidence of an ICCOA GPS *receipt gap*, not proof
 * that satellites, the GNSS HAL or OEM DR stopped working.
 *
 * This class never emits or replays a location, substitutes phone GPS, changes
 * DR admission, polls on a timer or issues control traffic on its own. One
 * attempt per continuously moving GPS gap; new genuine GPS receipt rearms it.
 */
internal class IccoaGpsActivationRecovery {
    companion object {
        const val GPS_SILENCE_REARM_MS = 10_000L
        private const val MAX_GEAR_OBSERVATION_GAP_MS = 5_000L
    }

    data class Trigger(val kind: Kind, val elapsedMs: Long) {
        enum class Kind { FIRST_GPS_NOT_RECEIVED, GPS_RECEIPT_STOPPED }
    }

    data class Evidence(
        val attempts: Int,
        val gpsReceiptsAfterAttempt: Int,
        val initialAttempts: Int,
        val postFixAttempts: Int,
        val lastReason: String,
        val lastSilenceMs: Long,
        val frozen: Boolean
    ) {
        fun reportLine(): String = listOf(
            "format=ICCOA_GPS_ACTIVATION_RECOVERY_V1",
            "policy=CAR_GPS_PRIMARY_NO_PHONE_FALLBACK",
            "attempts=$attempts",
            "initialAttempts=$initialAttempts postFixAttempts=$postFixAttempts",
            "gpsReceiptsAfterAttempt=$gpsReceiptsAfterAttempt",
            "lastReason=$lastReason lastSilenceMs=$lastSilenceMs",
            "frozenAtDisconnect=$frozen",
            "outgoingControl=EXISTING_OEM_CONTROL11_ONLY",
            "controlQueueSuccessAndHuEffect=REQUIRES_FIELD_LOGS",
            "satellitesActuallyLost=UNKNOWN",
            "oemDeadReckoningInjected=NO"
        ).joinToString("\n", postfix = "\n")
    }

    private var active = false
    private var lastGpsReceiptMonoMs = 0L
    private var movingSinceMonoMs = 0L
    private var lastMovingMonoMs = 0L
    private var movingSamples = 0
    private var attemptedCurrentGap = false
    private var attempts = 0
    private var initialAttempts = 0
    private var postFixAttempts = 0
    private var gpsReceiptsAfterAttempt = 0
    private var lastReason = "NONE"
    private var lastSilenceMs = 0L

    @Synchronized fun beginSession() {
        active = true
        lastGpsReceiptMonoMs = 0L
        movingSinceMonoMs = 0L
        lastMovingMonoMs = 0L
        movingSamples = 0
        attemptedCurrentGap = false
        attempts = 0
        initialAttempts = 0
        postFixAttempts = 0
        gpsReceiptsAfterAttempt = 0
        lastReason = "NONE"
        lastSilenceMs = 0L
    }

    @Synchronized fun endSession() {
        active = false
    }

    /** Only a received and decoded OEM GPS event resets the one-shot latch. */
    @Synchronized fun onGps(receiptMonoMs: Long) {
        if (!active || receiptMonoMs <= 0L) return
        if (lastGpsReceiptMonoMs > receiptMonoMs) return
        if (attemptedCurrentGap) gpsReceiptsAfterAttempt++
        lastGpsReceiptMonoMs = receiptMonoMs
        movingSinceMonoMs = 0L
        lastMovingMonoMs = 0L
        movingSamples = 0
        attemptedCurrentGap = false
    }

    /**
     * Called ONLY on an accepted ICCOA GearInfo message while a verified
     * receiver has an active OEM sensor-demand state. Positive speed in km/h
     * is a motion hint, not wheel odometry and never a source of coordinates.
     */
    @Synchronized fun onGear(
        speedKmh: Long?, receiptMonoMs: Long, authenticatedSensorDemand: Boolean
    ): Trigger? {
        if (!active || !authenticatedSensorDemand || receiptMonoMs <= 0L ||
            speedKmh == null || speedKmh !in 0L..234L) return null
        if (speedKmh == 0L) {
            movingSinceMonoMs = 0L
            lastMovingMonoMs = 0L
            movingSamples = 0
            return null
        }
        if (lastMovingMonoMs == 0L || receiptMonoMs <= lastMovingMonoMs ||
            receiptMonoMs - lastMovingMonoMs > MAX_GEAR_OBSERVATION_GAP_MS) {
            movingSinceMonoMs = receiptMonoMs
            movingSamples = 0
        }
        lastMovingMonoMs = receiptMonoMs
        movingSamples++
        if (attemptedCurrentGap || movingSamples < 2) return null
        val baseline = if (lastGpsReceiptMonoMs > 0L)
            lastGpsReceiptMonoMs else movingSinceMonoMs
        if (baseline <= 0L || receiptMonoMs < baseline ||
            receiptMonoMs - baseline < GPS_SILENCE_REARM_MS) return null

        attemptedCurrentGap = true
        attempts++
        lastSilenceMs = receiptMonoMs - baseline
        val kind = if (lastGpsReceiptMonoMs > 0L)
            Trigger.Kind.GPS_RECEIPT_STOPPED else Trigger.Kind.FIRST_GPS_NOT_RECEIVED
        if (kind == Trigger.Kind.GPS_RECEIPT_STOPPED) postFixAttempts++
        else initialAttempts++
        lastReason = kind.name
        return Trigger(kind, lastSilenceMs)
    }

    @Synchronized fun evidence(): Evidence = Evidence(
        attempts, gpsReceiptsAfterAttempt, initialAttempts, postFixAttempts,
        lastReason, lastSilenceMs, !active
    )
}

/** Keeps the latest connection's tiny, coordinate-free evidence for field ZIPs. */
internal object IccoaGpsActivationRecoveryEvidence {
    @Volatile private var observer: IccoaGpsActivationRecovery? = null

    fun attach(recovery: IccoaGpsActivationRecovery) {
        observer = recovery
    }

    fun snapshot(): String = observer?.evidence()?.reportLine()
        ?: "format=ICCOA_GPS_ACTIVATION_RECOVERY_V1\nstatus=NO_SESSION\n"
}
