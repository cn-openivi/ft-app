package app.ft.dr

import org.junit.Assert.*
import org.junit.Test

/** Real FT182 parking trace has raw events without verified wheel/yaw provenance. */
class DrPathEvidenceTest {
    @Test fun rawOemFramesDoNotClaimEstimatedPositionOrAaFusedDelivery() {
        DrPathEvidence.beginSession()
        DrPathEvidence.rawGps(10_000L)
        DrPathEvidence.rawGear()
        DrPathEvidence.rawGyro()
        DrPathEvidence.rawAcceleration()
        DrPathEvidence.aaLocationWritten(fused = false)
        DrPathEvidence.endSession()
        val result = DrPathEvidence.snapshot()
        assertFalse(result.active)
        assertEquals(1L, result.rawGps)
        assertEquals(1L, result.rawGear)
        assertEquals(1L, result.rawGyro)
        assertEquals(1L, result.rawAcceleration)
        assertEquals(0L, result.verifiedMotionSubmitted)
        assertEquals(0L, result.ekfPublished)
        assertEquals(0L, result.aaFusedWritten)
        assertEquals(1L, result.aaRawWritten)
        assertEquals("RAW_GNSS_WITHOUT_VERIFIED_ACCURACY_FIX_TIME", result.stage)
        assertTrue(result.reportLine(98_600L).contains("lastRawGpsReceiptAgeMs=88600"))
    }

    @Test fun trustedPipelineCountsEachStageSeparately() {
        DrPathEvidence.beginSession()
        DrPathEvidence.rawGps(30_000L)
        DrPathEvidence.rawGear()
        DrPathEvidence.rawGyro()
        DrPathEvidence.verifiedGnssSubmitted(true)
        DrPathEvidence.verifiedMotionSubmitted(true)
        DrPathEvidence.ekfPublished()
        DrPathEvidence.ftFusedPublished()
        DrPathEvidence.aaLocationWritten(fused = true)
        val result = DrPathEvidence.snapshot()
        assertEquals("AA_VERIFIED_FUSED_SOCKET_WRITTEN", result.stage)
        assertEquals(1L, result.verifiedGnssAdmitted)
        assertEquals(1L, result.verifiedMotionSubmitted)
        assertEquals(1L, result.ekfPublished)
        assertEquals(1L, result.ftFusedPublished)
        assertEquals(1L, result.aaFusedWritten)
        assertEquals(0L, result.aaRawWritten)
    }

    @Test fun aaSocketWritesFromOldSessionCannotTaintNewVehicle() {
        DrPathEvidence.beginSession()
        val previous = DrPathEvidence.sessionId()
        DrPathEvidence.beginSession()
        val current = DrPathEvidence.sessionId()
        DrPathEvidence.aaLocationWritten(fused = true, expectedSession = previous)
        assertEquals(0L, DrPathEvidence.snapshot().aaFusedWritten)
        DrPathEvidence.aaLocationWritten(fused = true, expectedSession = current)
        assertEquals(1L, DrPathEvidence.snapshot().aaFusedWritten)
        DrPathEvidence.endSession()
        DrPathEvidence.aaLocationWritten(fused = false, expectedSession = current)
        assertEquals(0L, DrPathEvidence.snapshot().aaRawWritten)
    }

    @Test fun onlyNewCarSessionResetsCompletedEvidence() {
        DrPathEvidence.beginSession()
        DrPathEvidence.rawGps(100L)
        DrPathEvidence.verifiedGnssSubmitted(false)
        DrPathEvidence.verifiedMotionSubmitted(false)
        DrPathEvidence.endSession()
        val old = DrPathEvidence.snapshot()
        assertEquals(1L, old.rawGps)
        assertEquals(1L, old.verifiedGnssRejected)
        assertEquals(1L, old.unverifiedMotionRejected)
        DrPathEvidence.beginSession()
        val fresh = DrPathEvidence.snapshot()
        assertTrue(fresh.active)
        assertEquals(old.session + 1L, fresh.session)
        assertEquals(0L, fresh.rawGps)
        assertEquals(0L, fresh.verifiedGnssRejected)
        assertEquals(0L, fresh.unverifiedMotionRejected)
        assertEquals("NO_OEM_GPS_EVENTS", fresh.stage)
    }
}
