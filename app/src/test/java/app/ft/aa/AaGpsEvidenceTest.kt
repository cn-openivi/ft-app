package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaGpsEvidenceTest {
    @Test fun advertisedGpsDoesNotImplySubscriptionOrLocationUse() {
        val session = AaGpsEvidence.begin(developerSelfMode = true)
        session.discovery(offered = true, characterization = 0x100, freshFix = true)
        assertEquals("AA_SENSOR_CHANNEL_NOT_OPENED", AaGpsEvidence.latest()!!.diagnosis())
        session.channelOpened()
        assertEquals("AA_LOCATION_NOT_REQUESTED", AaGpsEvidence.latest()!!.diagnosis())
        assertEquals(0L, AaGpsEvidence.latest()!!.eventsFlushed)
        assertTrue(AaGpsEvidence.summary().contains("navigationAppConsumed=UNKNOWN"))
    }

    @Test fun onlyRealRequestAndSocketFlushIncreaseEvidence() {
        val session = AaGpsEvidence.begin(developerSelfMode = true)
        session.discovery(true, 256, false)
        session.channelOpened()
        session.eventSubmitted()
        session.socketWriteFlushed() // no request -> ignored
        assertEquals(0L, AaGpsEvidence.latest()!!.eventsFlushed)
        session.locationRequested()
        assertEquals("LOCATION_REQUESTED_NO_SOCKET_WRITE", AaGpsEvidence.latest()!!.diagnosis())
        session.eventSubmitted()
        assertEquals(1L, AaGpsEvidence.latest()!!.eventsSubmitted)
        assertEquals(0L, AaGpsEvidence.latest()!!.eventsFlushed)
        session.socketWriteFlushed()
        assertEquals("LOCATION_SOCKET_WRITTEN_PHONE_CONSUMPTION_UNKNOWN",
            AaGpsEvidence.latest()!!.diagnosis())
        assertEquals(1L, AaGpsEvidence.latest()!!.eventsFlushed)
        assertTrue(AaGpsEvidence.summary().contains("phoneGpsUse=UNKNOWN"))
    }

    @Test fun reopeningSensorChannelInvalidatesSubscriptionNotHistoricalProof() {
        val session = AaGpsEvidence.begin(false)
        session.discovery(true, 0x100, true)
        session.channelOpened()
        session.locationRequested()
        session.channelClosed()
        session.channelOpened()
        session.eventSubmitted()
        assertFalse(AaGpsEvidence.latest()!!.currentChannelRequested)
        assertEquals(0L, AaGpsEvidence.latest()!!.eventsSubmitted)
        assertEquals(1, AaGpsEvidence.latest()!!.locationRequests)
    }

    @Test fun oldSessionsCannotChangeNewSessionAndFinishedEvidenceIsRetained() {
        val old = AaGpsEvidence.begin(true)
        old.discovery(true, 256, true)
        val fresh = AaGpsEvidence.begin(false)
        old.locationRequested()
        assertEquals("NO_SERVICE_DISCOVERY", AaGpsEvidence.latest()!!.diagnosis())
        fresh.discovery(false, 0x93, true)
        fresh.finish()
        fresh.channelOpened()
        assertTrue(AaGpsEvidence.latest()!!.ended)
        assertEquals(0, AaGpsEvidence.latest()!!.locationCharacterization)
        assertEquals("CAR_LOCATION_NOT_ADVERTISED", AaGpsEvidence.latest()!!.diagnosis())
    }
}
