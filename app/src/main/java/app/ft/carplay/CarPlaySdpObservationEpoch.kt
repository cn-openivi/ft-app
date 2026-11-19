package app.ft.carplay

/**
 * Only one read-only SDP observation may own the device broadcast listener.
 * A stale UUID broadcast, timer, or callback cannot finish a newer Toyota
 * probe. Never contains a Bluetooth identity, MAC, SDP UUID or packet bytes.
 */
internal class CarPlaySdpObservationEpoch {
    private var serial = 0L
    private var active: Long? = null

    @Synchronized fun begin(): Long {
        val next = ++serial
        active = next
        return next
    }

    @Synchronized fun accepts(generation: Long): Boolean =
        generation > 0L && active == generation

    @Synchronized fun complete(generation: Long): Boolean {
        if (!accepts(generation)) return false
        active = null
        return true
    }

    @Synchronized fun close() {
        serial++
        active = null
    }
}
