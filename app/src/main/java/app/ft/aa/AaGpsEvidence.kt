package app.ft.aa

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Observation only. AA phone decides whether to subscribe to car LOCATION.
 * No GPS policy override, speculative subscription or outgoing wire message.
 *
 * Counts contain no coordinates, phone identity, road data or private token.
 */
internal data class AaGpsEvidenceSnapshot(
    val sessionId: Long,
    val developerSelfMode: Boolean,
    val locationOffered: Boolean = false,
    val locationCharacterization: Int = 0,
    val oemFreshAtDiscovery: Boolean = false,
    val discoveryReceived: Boolean = false,
    val channelEverOpened: Boolean = false,
    val channelOpen: Boolean = false,
    val locationRequests: Int = 0,
    val currentChannelRequested: Boolean = false,
    val eventsSubmitted: Long = 0L,
    val eventsFlushed: Long = 0L,
    val ended: Boolean = false
) {
    /** Neither a flushed socket nor an opened channel proves app-level GPS use. */
    fun diagnosis(): String = when {
        !discoveryReceived -> "NO_SERVICE_DISCOVERY"
        !locationOffered -> "CAR_LOCATION_NOT_ADVERTISED"
        !channelEverOpened -> "AA_SENSOR_CHANNEL_NOT_OPENED"
        locationRequests == 0 -> "AA_LOCATION_NOT_REQUESTED"
        eventsFlushed == 0L -> "LOCATION_REQUESTED_NO_SOCKET_WRITE"
        else -> "LOCATION_SOCKET_WRITTEN_PHONE_CONSUMPTION_UNKNOWN"
    }

    fun reportLine(): String =
        "session=$sessionId mode=${if (developerSelfMode) "DEVELOPER_SELF_MODE" else "INBOUND_TRANSPORT"} " +
            "ended=$ended locationOffered=$locationOffered " +
            "characterization=$locationCharacterization " +
            "oemFreshAtDiscovery=$oemFreshAtDiscovery " +
            "sensorChannelOpened=$channelEverOpened sensorChannelCurrentlyOpen=$channelOpen " +
            "locationRequests=$locationRequests activeLocationSubscription=$currentChannelRequested " +
            "eventsSubmitted=$eventsSubmitted socketWritesFlushed=$eventsFlushed " +
            "diagnosis=${diagnosis()} phoneGpsUse=UNKNOWN navigationAppConsumed=UNKNOWN"
}

/**
 * Most recent Android Auto session only. Older sessions cannot overwrite it.
 * A new session owns its own recorder and may end after a newer session begins.
 */
internal object AaGpsEvidence {
    private val ids = AtomicLong()
    private val latestState = AtomicReference<AaGpsEvidenceSnapshot?>(null)

    @Synchronized fun begin(developerSelfMode: Boolean): Recorder {
        val state = AaGpsEvidenceSnapshot(ids.incrementAndGet(), developerSelfMode)
        latestState.set(state)
        return Recorder(state.sessionId)
    }

    fun latest(): AaGpsEvidenceSnapshot? = latestState.get()
    fun summary(): String = latest()?.reportLine() ?: "no Android Auto GPS session recorded"

    internal class Recorder(private val id: Long) {
        fun snapshot(): AaGpsEvidenceSnapshot? = AaGpsEvidence.latestState.get()?.takeIf { it.sessionId == id }
        fun summary(): String = snapshot()?.reportLine() ?: "older session superseded"

        private fun change(update: (AaGpsEvidenceSnapshot) -> AaGpsEvidenceSnapshot) {
            while (true) {
                val old = AaGpsEvidence.latestState.get() ?: return
                if (old.sessionId != id || old.ended) return
                if (AaGpsEvidence.latestState.compareAndSet(old, update(old))) return
            }
        }

        fun discovery(offered: Boolean, characterization: Int, freshFix: Boolean) =
            change { it.copy(discoveryReceived = true, locationOffered = offered,
                locationCharacterization = if (offered) characterization else 0,
                oemFreshAtDiscovery = freshFix) }

        fun channelOpened() = change {
            it.copy(channelEverOpened = true, channelOpen = true, currentChannelRequested = false)
        }

        fun channelClosed() = change {
            it.copy(channelOpen = false, currentChannelRequested = false)
        }

        fun locationRequested() = change {
            if (!it.channelOpen || !it.locationOffered) it
            else it.copy(locationRequests = it.locationRequests + 1, currentChannelRequested = true)
        }

        fun eventSubmitted() = change {
            if (it.currentChannelRequested && it.channelOpen)
                it.copy(eventsSubmitted = it.eventsSubmitted + 1) else it
        }

        fun socketWriteFlushed() = change {
            if (it.locationRequests > 0)
                it.copy(eventsFlushed = it.eventsFlushed + 1) else it
        }

        fun finish() = change { it.copy(ended = true, channelOpen = false, currentChannelRequested = false) }
    }
}
