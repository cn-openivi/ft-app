package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayHfpContinuityLedgerTest {
    private val same = CarPlaySingleBondPolicy.Result.HFP_CONNECTED_SAME_HU
    private val other = CarPlaySingleBondPolicy.Result.HFP_CONNECTED_OTHER_HU
    private val gone = CarPlaySingleBondPolicy.Result.HFP_DISCONNECTED

    @Test fun realReadOnlyProfileObservationNeverProvesCarrierSpeech() {
        val q = CarPlayHfpContinuityLedger()
        assertTrue(q.beginProbe(1))
        assertTrue(q.beginRead(1, 1))
        assertEquals(CarPlayHfpContinuityLedger.State.SAME_HU_PROFILE_ONLY, q.observe(1, 1, same, 100))
        assertEquals(CarPlayHfpContinuityLedger.State.SAME_HU_PROFILE_ONLY, q.state(101))
        assertEquals(CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED, q.observe(1, 1, gone, 102))
    }

    @Test fun secondRequestToSameToyotaWinsOverOldCallback() {
        val q = CarPlayHfpContinuityLedger()
        q.beginProbe(1)
        q.beginRead(1, 10)
        assertTrue(q.beginRead(1, 11))
        assertEquals(CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED, q.observe(1, 10, same, 90))
        assertEquals(CarPlayHfpContinuityLedger.State.UNOBSERVED, q.state(91))
        assertEquals(CarPlayHfpContinuityLedger.State.HFP_DISCONNECTED, q.observe(1, 11, gone, 100))
        assertEquals(CarPlayHfpContinuityLedger.State.HFP_DISCONNECTED, q.state(101))
    }

    @Test fun previousToyotaCallbackCannotCorruptNewSession() {
        val q = CarPlayHfpContinuityLedger()
        q.beginProbe(3)
        q.beginRead(3, 11)
        q.observe(3, 11, other, 100)
        q.beginProbe(4)
        assertEquals(CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED, q.observe(3, 11, same, 101))
        assertTrue(q.beginRead(4, 12))
        assertEquals(CarPlayHfpContinuityLedger.State.SAME_HU_PROFILE_ONLY, q.observe(4, 12, same, 110))
    }

    @Test fun repeatedRequestsRevokePreviouslyPositiveProfile() {
        val q = CarPlayHfpContinuityLedger()
        q.beginProbe(1)
        q.beginRead(1, 1)
        q.observe(1, 1, same, 100)
        q.beginRead(1, 2)
        assertEquals(CarPlayHfpContinuityLedger.State.UNOBSERVED, q.state(101))
        assertEquals(CarPlayHfpContinuityLedger.State.HFP_DISCONNECTED, q.observe(1, 2, gone, 200))
        q.beginRead(1, 3)
        assertEquals(CarPlayHfpContinuityLedger.State.DIFFERENT_HU, q.observe(1, 3, other, 300))
        q.beginRead(1, 4)
        assertEquals(CarPlayHfpContinuityLedger.State.PROFILE_UNAVAILABLE,
            q.observe(1, 4, CarPlaySingleBondPolicy.Result.BT_STATE_UNAVAILABLE, 301))
    }

    @Test fun expiredAndNonMonotonicReadCannotRemainConfirmed() {
        val q = CarPlayHfpContinuityLedger(freshnessMs = 100)
        q.beginProbe(2)
        q.beginRead(2, 1)
        q.observe(2, 1, same, 100)
        assertEquals(CarPlayHfpContinuityLedger.State.SAME_HU_PROFILE_ONLY, q.state(200))
        assertEquals(CarPlayHfpContinuityLedger.State.EXPIRED, q.state(201))
        assertEquals(CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED, q.observe(2, 1, same, 99))
        assertFalse(q.beginRead(2, 1))
        assertTrue(q.beginRead(2, 2))
        assertEquals(CarPlayHfpContinuityLedger.State.UNOBSERVED, q.state(201))
        assertEquals(CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED, q.observe(2, 2, same, -1))
        assertEquals(CarPlayHfpContinuityLedger.State.SAME_HU_PROFILE_ONLY, q.observe(2, 2, same, 250))
    }

    @Test fun noBondOrUnauthorizedProbeCannotPass() {
        val q = CarPlayHfpContinuityLedger()
        assertFalse(q.beginRead(0, 1))
        assertTrue(q.beginProbe(5))
        assertFalse(q.beginRead(4, 1))
        assertTrue(q.beginRead(5, 1))
        assertEquals(CarPlayHfpContinuityLedger.State.NO_BOND,
            q.observe(5, 1, CarPlaySingleBondPolicy.Result.NO_EXISTING_BOND, 10))
        assertEquals(CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED, q.observe(5, 1, same, 11))
    }
}
