package app.ft.carplay

/**
 * FT200.2 DNS-SD callback consistency for one real Android Network discovery.
 * The platform may deliver SERVICE_LOST before a pending asynchronous resolve.
 * An old resolved endpoint must never outlive the service or a new FT session.
 *
 * No service names, device IDs, IP addresses or keys are logged/persisted.
 */
internal class CarPlayAirPlayNsdResolutionFence {
    class Attempt internal constructor(
        internal val generation: Long,
        internal val serial: Long,
        internal val serviceName: String
    ) {
        override fun toString() = "NsdResolveAttempt(redacted)"
    }

    private var generation = 0L
    private var serial = 0L
    private var pending: Attempt? = null

    @Synchronized
    fun begin(nextGeneration: Long): Boolean {
        if (nextGeneration <= generation || nextGeneration <= 0L) return false
        generation = nextGeneration
        pending = null
        return true
    }

    @Synchronized
    fun beginResolution(expectedGeneration: Long, serviceName: String?): Attempt? {
        if (expectedGeneration != generation || pending != null ||
            serviceName.isNullOrBlank()) return null
        val attempt = Attempt(generation, ++serial, serviceName)
        pending = attempt
        return attempt
    }

    /** The result must correspond to the exact currently pending resolved service. */
    @Synchronized
    fun complete(attempt: Attempt, resolvedName: String?): Boolean {
        if (attempt.generation != generation || pending !== attempt ||
            attempt.serial != serial) return false
        pending = null
        // A name-mismatched response consumes its attempt, preventing a
        // poisoned resolution from indefinitely blocking discovery.
        return resolvedName == attempt.serviceName
    }

    @Synchronized
    fun serviceLost(expectedGeneration: Long, serviceName: String?): Boolean {
        if (expectedGeneration != generation || serviceName.isNullOrBlank()) return false
        if (pending?.serviceName == serviceName) {
            pending = null
            serial++ // Late resolution from the vanished service is invalid.
        }
        return true
    }

    @Synchronized
    fun close() {
        generation++
        pending = null
        serial++
    }
}
