package app.ft.iccoa

/**
 * FAW Toyota live OEM GPS timestamp provenance: observed Android wall-clock
 * milliseconds. A newly arrived frame can contain yesterday's cached fix.
 * Unknown clock scales fail closed, without guessing timestamps from GearInfo.
 */
internal object IccoaGpsTimestampGate {
    private const val EARLIEST_EPOCH_MS = 1_577_836_800_000L // 2020-01-01 UTC
    private const val MAX_MEASUREMENT_AGE_MS = 15_000L
    private const val MAX_CLOCK_LEAD_MS = 2_000L

    fun measuredAtMs(oemTimestampRaw: Long, receivedAtMs: Long): Long? {
        if (oemTimestampRaw < EARLIEST_EPOCH_MS || receivedAtMs < EARLIEST_EPOCH_MS) return null
        val ageMs = receivedAtMs - oemTimestampRaw
        if (ageMs !in -MAX_CLOCK_LEAD_MS..MAX_MEASUREMENT_AGE_MS) return null
        return oemTimestampRaw.coerceAtMost(receivedAtMs)
    }
}
