package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayTrialProgressTest {
    @Test fun physicalLinkAndIdentificationDoNotProveCarPlay() {
        var s = CarPlayTrialProgress.start()
        for (event in listOf(
            CarPlayIncomingIapProbe.Observation.SDP_LISTENER_READY,
            CarPlayIncomingIapProbe.Observation.ACTUAL_TOYOTA_RFCOMM_CONNECTED,
            CarPlayIncomingIapProbe.Observation.LINK_NEGOTIATED,
            CarPlayIncomingIapProbe.Observation.IDENTIFICATION_REQUEST_SENT,
            CarPlayIncomingIapProbe.Observation.IDENTIFICATION_INFO_OBSERVED,
            CarPlayIncomingIapProbe.Observation.WIFI_DIALECT_FLAT_HINT
        )) s = CarPlayTrialProgress.observe(s, event)
        assertEquals(CarPlayTrialProgress.Phase.WIFI_DIALECT_OBSERVED, s.phase)
        assertTrue(s.realLinkObserved)
        assertTrue(s.realControlObserved)
        assertFalse(s.carPlayAuthenticated)
        assertFalse(s.carPlayActive)
        assertFalse(s.terminal)
    }
    @Test fun untrustedCertificateAndResponseNeverFakeMfi() {
        var s = CarPlayTrialProgress.observe(CarPlayTrialProgress.start(),
            CarPlayIncomingIapProbe.Observation.ACCESSORY_AUTH_CERTIFICATE_RECEIVED_UNTRUSTED)
        assertEquals(CarPlayTrialProgress.Blocker.MFI_CERTIFICATE_UNTRUSTED, s.blocker)
        s = CarPlayTrialProgress.observe(s,
            CarPlayIncomingIapProbe.Observation.ACCESSORY_AUTH_RESPONSE_RECEIVED_UNVERIFIED)
        assertEquals(CarPlayTrialProgress.Phase.MFI_TRUST_REQUIRED, s.phase)
        assertEquals(CarPlayTrialProgress.Blocker.MFI_SIGNATURE_UNVERIFIED, s.blocker)
        assertFalse(s.carPlayAuthenticated)
        assertFalse(s.carPlayActive)
        assertEquals(CarPlayTrialProgress.Phase.MFI_TRUST_REQUIRED,
            CarPlayTrialProgress.observe(s,
                CarPlayIncomingIapProbe.Observation.IDENTIFICATION_REQUEST_ACKED).phase)
    }
    @Test fun noToyotaOrNoAckReportsExactFailureAndRejectsLateEvents() {
        val a = CarPlayTrialProgress.observe(CarPlayTrialProgress.start(),
            CarPlayIncomingIapProbe.Observation.ACCEPT_TIMEOUT)
        assertTrue(a.terminal)
        assertEquals(CarPlayTrialProgress.Blocker.NO_TOYOTA_RFCOMM, a.blocker)
        assertEquals(a, CarPlayTrialProgress.observe(a,
            CarPlayIncomingIapProbe.Observation.LINK_NEGOTIATED))
        val b = CarPlayTrialProgress.observe(CarPlayTrialProgress.start(),
            CarPlayIncomingIapProbe.Observation.LINK_ACK_NOT_OBSERVED)
        assertEquals(CarPlayTrialProgress.Blocker.IAP2_LINK_FAILED, b.blocker)
    }
    @Test fun timeoutPreservesFurthestRealHandshakePhaseAndMfiBlocker() {
        var s = CarPlayTrialProgress.start()
        s = CarPlayTrialProgress.observe(s,
            CarPlayIncomingIapProbe.Observation.LINK_NEGOTIATED)
        s = CarPlayTrialProgress.observe(s,
            CarPlayIncomingIapProbe.Observation.PROBE_TIMEOUT)
        assertTrue(s.terminal)
        assertEquals(CarPlayTrialProgress.Phase.IAP2_LINK_NEGOTIATED, s.phase)
        assertTrue(s.realLinkObserved)
        assertEquals(CarPlayTrialProgress.Blocker.IO_OR_TIMEOUT, s.blocker)

        var auth = CarPlayTrialProgress.start()
        auth = CarPlayTrialProgress.observe(auth,
            CarPlayIncomingIapProbe.Observation.ACCESSORY_AUTH_CERTIFICATE_RECEIVED_UNTRUSTED)
        auth = CarPlayTrialProgress.observe(auth,
            CarPlayIncomingIapProbe.Observation.PROBE_TIMEOUT)
        assertTrue(auth.terminal)
        assertEquals(CarPlayTrialProgress.Phase.MFI_TRUST_REQUIRED, auth.phase)
        assertEquals(CarPlayTrialProgress.Blocker.MFI_CERTIFICATE_UNTRUSTED, auth.blocker)
        assertFalse(auth.carPlayActive)
    }

    @Test fun ownerStopAndRetryResetEvidence() {
        val old = CarPlayTrialProgress.observe(CarPlayTrialProgress.start(),
            CarPlayIncomingIapProbe.Observation.CANCELLED)
        assertTrue(old.terminal)
        assertEquals(CarPlayTrialProgress.Blocker.STOPPED_BY_OWNER, old.blocker)
        val fresh = CarPlayTrialProgress.start()
        assertFalse(fresh.realLinkObserved)
        assertEquals(CarPlayTrialProgress.Blocker.NONE, fresh.blocker)
        assertFalse(fresh.carPlayActive)
    }
    @Test fun ackAloneDoesNotClaimLinkOrControl() {
        val s = CarPlayTrialProgress.observe(CarPlayTrialProgress.start(),
            CarPlayIncomingIapProbe.Observation.LINK_DATA_ACK_SENT)
        assertEquals(CarPlayTrialProgress.Phase.WAITING_FOR_TOYOTA, s.phase)
        assertFalse(s.realLinkObserved)
        assertFalse(s.carPlayAuthenticated)
    }
}
