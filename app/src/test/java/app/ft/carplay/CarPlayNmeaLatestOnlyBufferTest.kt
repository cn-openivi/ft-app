package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayNmeaLatestOnlyBufferTest {
    private val Activation = CarPlayNmeaSubscriptionSession.Activation.ACTIVATED_FOR_ENCODING_ONLY
    private val approved = CarPlayNmeaSubscriptionSession.Emission(
        CarPlayNmeaSubscriptionSession.Outcome.ENCODED_NOT_SENT,
        CarPlayNmeaSentences.PairOfSentences(
            "${'$'}GPGGA,redacted", "${'$'}GPRMC,redacted"
        )
    )

    @Test fun onlyMostRecentFixIsOfferedAndNeverReplayOldOnes() {
        val buffer = CarPlayNmeaLatestOnlyBuffer()
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.ACTIVATED, buffer.begin(1, Activation))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.QUEUED,
            buffer.offer(1, 1700000000100L, approved))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.REPLACED_OLDER_PENDING,
            buffer.offer(1, 1700000000200L, approved))
        val candidate = buffer.poll(1)!!
        assertEquals(1700000000200L, candidate.utcEpochMillis)
        assertNull(buffer.poll(1))
        assertFalse(candidate.toString().contains("GPGGA"))
    }

    @Test fun rejectDuplicateAndFailedOrUnapprovedEmissions() {
        val buffer = CarPlayNmeaLatestOnlyBuffer()
        buffer.begin(2, Activation)
        buffer.offer(2, 1700000000100L, approved)
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.DUPLICATE_OR_OLDER_FIX,
            buffer.offer(2, 1700000000100L, approved))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.EMISSION_NOT_APPROVED,
            buffer.offer(2, 1700000000200L,
                CarPlayNmeaSubscriptionSession.Emission(
                    CarPlayNmeaSubscriptionSession.Outcome.SOURCE_REJECTED)))
        assertEquals(1700000000100L, buffer.poll(2)!!.utcEpochMillis)
    }

    @Test fun newAuthFailureOrSessionLossRevokesPendingFix() {
        val buffer = CarPlayNmeaLatestOnlyBuffer()
        buffer.begin(3, Activation)
        buffer.offer(3, 1700000000100L, approved)
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.ACTIVATION_DENIED,
            buffer.begin(4, CarPlayNmeaSubscriptionSession.Activation.HU_REQUEST_MISSING))
        assertNull(buffer.poll(3))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.NO_AUTHORIZED_SESSION,
            buffer.offer(4, 1700000000200L, approved))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.ACTIVATED, buffer.begin(5, Activation))
        buffer.offer(5, 1700000000300L, approved)
        buffer.onSessionLost(4)
        assertNotNull(buffer.poll(5))
        buffer.offer(5, 1700000000400L, approved)
        buffer.onSessionLost(5)
        assertNull(buffer.poll(5))
    }

    @Test fun staleGenerationAndAbsurdTimestampsCannotReachTransport() {
        val buffer = CarPlayNmeaLatestOnlyBuffer()
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.STALE_GENERATION,
            buffer.begin(0, Activation))
        buffer.begin(7, Activation)
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.STALE_GENERATION,
            buffer.begin(7, Activation))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.EMISSION_NOT_APPROVED,
            buffer.offer(7, -1L, approved))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.EMISSION_NOT_APPROVED,
            buffer.offer(7, 4102444800001L, approved))
        assertNull(buffer.poll(7))
    }
}
