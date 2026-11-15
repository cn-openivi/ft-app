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
}
