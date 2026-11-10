package app.ft.iccoa

/**
 * FAW Carlink Gps timestamp is produced while serializing a sensor packet.
 * It is NOT a proven GNSS measurement epoch, so it cannot be a 3s/15s age
 * rejection criterion. Keep OEM timestamp separately in raw telemetry.
 */
internal object IccoaGpsTimestampGate {
    fun observedAtReceiveMs(receivedAtMs: Long): Long? =
        receivedAtMs.takeIf { it > 0L }
}
