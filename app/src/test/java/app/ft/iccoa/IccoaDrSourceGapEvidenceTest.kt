package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaDrSourceGapEvidenceTest {
    @Test fun parkingStartupWithMovingGearButNoGpsRemainsAcquisitionDelayNotDr() {
        IccoaDrSourceGapEvidence.beginSession()
        IccoaDrSourceGapEvidence.onGear(22L, 1000L)
        IccoaDrSourceGapEvidence.onGear(49L, 75_000L)
        val first = IccoaDrSourceGapEvidence.onGps(81_000L)
        assertNotNull(first)
        assertEquals("FIRST_GPS_AFTER_MOVING_GEAR", first!!.kind)
        assertEquals(80_000L, first.elapsedMs)
        assertEquals(2, first.rawMovingGearFrames)
        val data = IccoaDrSourceGapEvidence.snapshot()
        assertEquals(80_000L, data.firstGearToFirstGpsMs)
        assertEquals(80_000L, data.firstMovingGearToFirstGpsMs)
        assertEquals(0L, data.maxInterGpsReceiptGapMs)
        assertTrue(data.reportLine().contains("gnssActuallyLost=UNKNOWN"))
        assertTrue(data.reportLine().contains("oemDeadReckoningProven=NO"))
    }

    @Test fun laterGapHasDifferentProvenanceThanInitialFixAndNeverPublishesDr() {
        IccoaDrSourceGapEvidence.beginSession()
        assertNull(IccoaDrSourceGapEvidence.onGps(1000L))
        IccoaDrSourceGapEvidence.onGear(40L, 4_000L)
        val gap = IccoaDrSourceGapEvidence.onGps(30_000L)
        assertNotNull(gap)
        assertEquals("INTER_GPS_RECEIPT_GAP_WITH_GEAR", gap!!.kind)
        assertEquals(29_000L, gap.elapsedMs)
        assertEquals(1, gap.rawMovingGearFrames)
        assertEquals(29_000L, IccoaDrSourceGapEvidence.snapshot().maxInterGpsReceiptGapMs)
        assertTrue(IccoaDrSourceGapEvidence.snapshot().reportLine()
            .contains("ftVerifiedEkfInputs=NOT_PRODUCED_BY_THIS_DIAGNOSTIC"))
    }

    @Test fun zeroSpeedOrOrdinaryOneSecondFixDoesNotClaimLoss() {
        IccoaDrSourceGapEvidence.beginSession()
        IccoaDrSourceGapEvidence.onGear(0L, 1000L)
        assertNull(IccoaDrSourceGapEvidence.onGps(80_000L))
        IccoaDrSourceGapEvidence.onGear(0L, 80_100L)
        assertNull(IccoaDrSourceGapEvidence.onGps(81_000L))
        assertEquals(1_000L, IccoaDrSourceGapEvidence.snapshot().maxInterGpsReceiptGapMs)
    }

    @Test fun reconnectStartsNewEvidenceEpoch() {
        IccoaDrSourceGapEvidence.beginSession()
        IccoaDrSourceGapEvidence.onGear(45L, 1000L)
        IccoaDrSourceGapEvidence.onGps(80_000L)
        IccoaDrSourceGapEvidence.beginSession()
        val fresh = IccoaDrSourceGapEvidence.snapshot()
        assertEquals(0L, fresh.gpsFrames)
        assertEquals(0L, fresh.gearFrames)
        assertNull(fresh.firstGearToFirstGpsMs)
    }

    @Test fun parkingGpsStopsAfterSuccessfulFixAndGearKeepsMovingUntilDisconnect() {
        IccoaDrSourceGapEvidence.beginSession()
        IccoaDrSourceGapEvidence.onGps(10_000L)
        IccoaDrSourceGapEvidence.onGps(11_000L)
        IccoaDrSourceGapEvidence.onGear(25L, 14_000L)
        IccoaDrSourceGapEvidence.onGear(8L, 22_000L)
        IccoaDrSourceGapEvidence.onGear(19L, 44_000L)
        val live = IccoaDrSourceGapEvidence.snapshot(66_700L)
        assertEquals(55_700L, live.postFixGpsSilenceWithMovingGearMs)
        assertFalse(live.snapshotEndedSession)
        // User exports the report much later; measure only until the HU disconnect.
        IccoaDrSourceGapEvidence.endSession(74_600L)
        val laterExport = IccoaDrSourceGapEvidence.snapshot(2_500_000L)
        assertEquals(63_600L, laterExport.postFixGpsSilenceWithMovingGearMs)
        assertTrue(laterExport.snapshotEndedSession)
        assertTrue(laterExport.reportLine().contains("FROZEN_AT_DISCONNECT"))
        assertEquals(1_000L, laterExport.maxInterGpsReceiptGapMs)
        assertNull(laterExport.initialGpsWaitWithMovingGearMs)
    }

    @Test fun ifNoGpsEverAppearedFreezeOnlyInitialMotionWait() {
        IccoaDrSourceGapEvidence.beginSession()
        IccoaDrSourceGapEvidence.onGear(9L, 1_000L)
        IccoaDrSourceGapEvidence.onGear(20L, 10_000L)
        IccoaDrSourceGapEvidence.endSession(85_000L)
        val exported = IccoaDrSourceGapEvidence.snapshot(8_000_000L)
        assertEquals(84_000L, exported.initialGpsWaitWithMovingGearMs)
        assertNull(exported.postFixGpsSilenceWithMovingGearMs)
        assertTrue(exported.reportLine().contains("gnssActuallyLost=UNKNOWN"))
    }
}
