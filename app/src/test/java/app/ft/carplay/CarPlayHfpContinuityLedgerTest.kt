package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayHfpContinuityLedgerTest {
    private val same = CarPlaySingleBondPolicy.Result.HFP_CONNECTED_SAME_HU
    private val other = CarPlaySingleBondPolicy.Result.HFP_CONNECTED_OTHER_HU
    private val gone = CarPlaySingleBondPolicy.Result.HFP_DISCONNECTED

    @Test fun observedProfileNeverClaimedToBeCarrierCallOrCarPlay() {
        val ledger = CarPlayHfpContinuityLedger()
        assertTrue(ledger.beginProbe(1))
        assertEquals(CarPlayHfpContinuityLedger.State.SAME_HU_PROFILE_ONLY, ledger.observe(1, same, 100))
        assertEquals(CarPlayHfpContinuityLedger.State.SAME_HU_PROFILE_ONLY, ledger.state(101))
    }

    @Test fun lateCallbackFromEarlierToyotaIsIgnored() {
        val ledger = CarPlayHfpContinuityLedger()
        ledger.beginProbe(3)
        ledger.observe(3, other, 100)
        ledger.beginProbe(4)
        assertEquals(CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED, ledger.observe(3, same, 101))
        assertEquals(CarPlayHfpContinuityLedger.State.UNOBSERVED, ledger.state(102))
        assertEquals(CarPlayHfpContinuityLedger.State.SAME_HU_PROFILE_ONLY, ledger.observe(4, same, 110))
    }

    @Test fun hfpLossOrPeerSwitchRevokesPreviouslyGoodObservation() {
        val ledger = CarPlayHfpContinuityLedger()
        ledger.beginProbe(1)
        ledger.observe(1, same, 100)
        assertEquals(CarPlayHfpContinuityLedger.State.HFP_DISCONNECTED, ledger.observe(1, gone, 200))
        assertEquals(CarPlayHfpContinuityLedger.State.DIFFERENT_HU, ledger.observe(1, other, 300))
        assertEquals(CarPlayHfpContinuityLedger.State.PROFILE_UNAVAILABLE,
            ledger.observe(1, CarPlaySingleBondPolicy.Result.BT_STATE_UNAVAILABLE, 301))
    }

    @Test fun expiredAndNonMonotonicResultsNeverRemainVerified() {
        val ledger = CarPlayHfpContinuityLedger(freshnessMs = 100)
        ledger.beginProbe(2)
        ledger.observe(2, same, 100)
        assertEquals(CarPlayHfpContinuityLedger.State.SAME_HU_PROFILE_ONLY, ledger.state(200))
        assertEquals(CarPlayHfpContinuityLedger.State.EXPIRED, ledger.state(201))
        assertEquals(CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED, ledger.observe(2, same, 99))
        assertEquals(CarPlayHfpContinuityLedger.State.EXPIRED, ledger.state(250))
        assertFalse(ledger.beginProbe(2))
        assertTrue(ledger.beginProbe(3))
        assertEquals(CarPlayHfpContinuityLedger.State.UNOBSERVED, ledger.state(250))
    }

    @Test fun noBondAndNoValidDiagnosticGenerationNeverPass() {
        val ledger = CarPlayHfpContinuityLedger()
        assertEquals(CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED, ledger.observe(0, same, 5))
        assertTrue(ledger.beginProbe(5))
        assertEquals(CarPlayHfpContinuityLedger.State.NO_BOND,
            ledger.observe(5, CarPlaySingleBondPolicy.Result.NO_EXISTING_BOND, 10))
        assertEquals(CarPlayHfpContinuityLedger.State.STALE_PROBE_IGNORED, ledger.observe(5, same, -1))
    }
}
