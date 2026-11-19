package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayNmeaLatestOnlyBufferTest {
    private val activation = CarPlayNmeaSubscriptionSession.Activation.ACTIVATED_FOR_ENCODING_ONLY

    private fun emission(epoch: Long): CarPlayNmeaSubscriptionSession.Emission {
        val session = CarPlayNmeaSubscriptionSession()
        assertEquals(activation, session.begin(1, true, true, true))
        return session.encode(1, CarPlayNmeaSentences.Fix(
            51.5074, -0.1278, epoch,
            CarPlayMediaPreflight.PositionSource.REAL_OEM_WGS84,
            false
        ), independentlyVerifiedWgs84 = true)
    }

    @Test fun onlyNewestFixIsOfferedAndNeverReplayed() {
        val q = CarPlayNmeaLatestOnlyBuffer()
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.ACTIVATED, q.begin(1, activation))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.QUEUED,
            q.offer(1, 1700000000100L, emission(1700000000100L), 100L))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.REPLACED_OLDER_PENDING,
            q.offer(1, 1700000000200L, emission(1700000000200L), 101L))
        val p = q.poll(1, 102L)!!
        assertEquals(1700000000200L, p.utcEpochMillis)
        assertNull(q.poll(1, 103L))
        assertFalse(p.toString().contains("51.5074"))
        assertFalse(p.toString().contains("GPGGA"))
    }

    @Test fun mismatchedEpochNeverChangesTheActualGpsPayloadEpoch() {
        val q = CarPlayNmeaLatestOnlyBuffer()
        q.begin(2, activation)
        val payload = emission(1700000000100L)
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.FIX_EPOCH_MISMATCH,
            q.offer(2, 1700000000200L, payload, 1L))
        assertNull(q.poll(2, 1L))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.QUEUED,
            q.offer(2, 1700000000100L, payload, 2L))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.DUPLICATE_OR_OLDER_FIX,
            q.offer(2, 1700000000100L, payload, 3L))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.EMISSION_NOT_APPROVED,
            q.offer(2, 1700000000200L,
                CarPlayNmeaSubscriptionSession.Emission(
                    CarPlayNmeaSubscriptionSession.Outcome.SOURCE_REJECTED), 4L))
        assertEquals(1700000000100L, q.poll(2, 4L)!!.utcEpochMillis)
    }

    @Test fun stalledGpsQueueExpiresAndBackwardClockFailsClosed() {
        val q = CarPlayNmeaLatestOnlyBuffer(maxPendingAgeMs = 100L)
        q.begin(3, activation)
        q.offer(3, 1700000000100L, emission(1700000000100L), 200L)
        assertNull(q.poll(3, 199L))
        q.offer(3, 1700000000200L, emission(1700000000200L), 200L)
        assertNotNull(q.poll(3, 300L))
        q.offer(3, 1700000000300L, emission(1700000000300L), 300L)
        assertNull(q.poll(3, 401L))
        assertNull(q.poll(3, 402L))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.INVALID_MONOTONIC_TIME,
            q.offer(3, 1700000000400L, emission(1700000000400L), -1L))
    }

    @Test fun failedNewAuthOrSessionLossRevokesPendingFix() {
        val q = CarPlayNmeaLatestOnlyBuffer()
        q.begin(3, activation)
        q.offer(3, 1700000000100L, emission(1700000000100L), 100L)
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.ACTIVATION_DENIED,
            q.begin(4, CarPlayNmeaSubscriptionSession.Activation.HU_REQUEST_MISSING))
        assertNull(q.poll(3, 101L))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.NO_AUTHORIZED_SESSION,
            q.offer(4, 1700000000200L, emission(1700000000200L), 200L))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.ACTIVATED, q.begin(5, activation))
        q.offer(5, 1700000000300L, emission(1700000000300L), 300L)
        q.onSessionLost(4)
        assertNotNull(q.poll(5, 301L))
        q.offer(5, 1700000000400L, emission(1700000000400L), 400L)
        q.onSessionLost(5)
        assertNull(q.poll(5, 401L))
    }

    @Test fun unauthorizedGenerationAndInvalidEpochNeverEnterQueue() {
        val q = CarPlayNmeaLatestOnlyBuffer()
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.STALE_GENERATION,
            q.begin(0, activation))
        q.begin(7, activation)
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.STALE_GENERATION,
            q.begin(7, activation))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.EMISSION_NOT_APPROVED,
            q.offer(7, -1L, emission(1700000000100L), 100L))
        assertEquals(CarPlayNmeaLatestOnlyBuffer.State.EMISSION_NOT_APPROVED,
            q.offer(7, 4102444800001L, emission(1700000000200L), 100L))
        assertNull(q.poll(7, 101L))
    }
}
