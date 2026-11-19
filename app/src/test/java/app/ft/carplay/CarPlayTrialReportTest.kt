package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayTrialReportTest {
    @Test fun reportIsRedactedAndNeverClaimsActiveFromReceivedControl() {
        var state = CarPlayTrialProgress.start()
        state = CarPlayTrialProgress.observe(state,
            CarPlayIncomingIapProbe.Observation.LINK_NEGOTIATED)
        state = CarPlayTrialProgress.observe(state,
            CarPlayIncomingIapProbe.Observation.ACCESSORY_AUTH_RESPONSE_RECEIVED_UNVERIFIED)
        val report = CarPlayTrialReport.format(state,
            CarPlayHfpContinuityLedger.State.SAME_HU_PROFILE_ONLY,
            listOf(CarPlayIncomingIapProbe.Event(
                CarPlayIncomingIapProbe.Observation.ACCESSORY_AUTH_RESPONSE_RECEIVED_UNVERIFIED,
                validLinkFrames = 1, messageId = 0xAA03, parameterCount = 3)))
        assertTrue(report.contains("phase=MFI_TRUST_REQUIRED"))
        assertTrue(report.contains("mfi_authenticated=UNVERIFIED"))
        assertTrue(report.contains("carplay_active=NO"))
        assertTrue(report.contains("real_cellular_2way_voice=NOT_TESTED"))
        assertTrue(report.contains("control=0xAA03"))
        assertFalse(report.contains("password"))
        assertFalse(report.contains("AA:BB:CC"))
    }

    @Test fun reportBoundsEventsAndNeverLeaksArbitraryData() {
        val events = (1..21).map {
            CarPlayIncomingIapProbe.Event(
                CarPlayIncomingIapProbe.Observation.CONTROL_MESSAGE_OBSERVED,
                validLinkFrames=it, messageId=0x4301, parameterCount=it)
        }
        val report = CarPlayTrialReport.format(CarPlayTrialProgress.start(),
            CarPlayHfpContinuityLedger.State.UNOBSERVED, events)
        assertEquals(14, report.lines().count { it.startsWith("CONTROL_MESSAGE_OBSERVED") })
        assertFalse(report.contains("link_frames=1;"))
        assertTrue(report.contains("link_frames=21;"))
        assertTrue(report.contains("hfp_profile=UNOBSERVED"))
    }
}
