package app.ft.iccoa

/**
 * Per-connection ICCOA SENSOR evidence. Service-level lifetime counters must
 * not hide a missing GPS producer on a newly authenticated car session.
 *
 * Decoding a GPS protobuf is not evidence that the OEM timestamp is fresh:
 * acceptedGpsFrames only counts locations that passed the timestamp gate.
 * This does not retry PIN/auth or issue new OEM control messages.
 */
internal class IccoaSensorSessionLiveness {
    var gpsFrames: Long = 0L
        private set
    var acceptedGpsFrames: Long = 0L
        private set
    var gearFrames: Long = 0L
        private set
    var acceptedGearFrames: Long = 0L
        private set

    fun onGps(accepted: Boolean) {
        gpsFrames++
        if (accepted) acceptedGpsFrames++
    }

    fun onGear(accepted: Boolean) {
        gearFrames++
        if (accepted) acceptedGearFrames++
    }

    // A decoded-but-stale GPS packet is still NO usable position. Do not
    // suppress the diagnostic merely because non-fresh protobufs arrived.
    fun gearWithoutAcceptedGps(): Boolean =
        gearFrames > 0L && acceptedGpsFrames == 0L

    fun reset() {
        gpsFrames = 0L
        acceptedGpsFrames = 0L
        gearFrames = 0L
        acceptedGearFrames = 0L
    }
}
