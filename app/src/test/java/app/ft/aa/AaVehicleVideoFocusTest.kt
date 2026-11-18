package app.ft.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AaVehicleVideoFocusTest {
    @Test fun defaultIsProjectedAndDoesNotEmitSpuriousEdge() {
        val state = AaVehicleVideoFocus()
        assertEquals(AaProtocol.VIDEO_FOCUS_PROJECTED, state.currentMode())
        assertNull(state.transition(true))
    }

    @Test fun oemHomeThenPlayCreatesRealNativeProjectedFocusEdge() {
        val state = AaVehicleVideoFocus()
        assertEquals(AaProtocol.VIDEO_FOCUS_NATIVE, state.transition(false))
        assertEquals(AaProtocol.VIDEO_FOCUS_NATIVE, state.currentMode())
        assertEquals(AaProtocol.VIDEO_FOCUS_PROJECTED, state.transition(true))
        assertEquals(AaProtocol.VIDEO_FOCUS_PROJECTED, state.currentMode())
    }

    @Test fun repeatedPauseAndResumeDoNotFloodFocusLane() {
        val state = AaVehicleVideoFocus()
        assertEquals(AaProtocol.VIDEO_FOCUS_NATIVE, state.transition(false))
        assertNull(state.transition(false))
        assertNull(state.transition(false))
        assertEquals(AaProtocol.VIDEO_FOCUS_PROJECTED, state.transition(true))
        assertNull(state.transition(true))
    }

    @Test fun aaStartAndPhoneFocusRequestsCannotReExposeHiddenOemHome() {
        val state = AaVehicleVideoFocus()
        state.transition(false)
        assertEquals(AaProtocol.VIDEO_FOCUS_NATIVE, state.currentMode())
        assertEquals(AaProtocol.VIDEO_FOCUS_NATIVE,
            state.replyToPhoneRequest(AaProtocol.VIDEO_FOCUS_PROJECTED))
        assertEquals(AaProtocol.VIDEO_FOCUS_NATIVE,
            state.replyToPhoneRequest(AaProtocol.VIDEO_FOCUS_NATIVE))
    }

    @Test fun requestsPreservePhoneChoiceWhenCarProjectionIsVisible() {
        val state = AaVehicleVideoFocus()
        assertEquals(AaProtocol.VIDEO_FOCUS_NATIVE,
            state.replyToPhoneRequest(AaProtocol.VIDEO_FOCUS_NATIVE))
        assertEquals(AaProtocol.VIDEO_FOCUS_PROJECTED,
            state.replyToPhoneRequest(AaProtocol.VIDEO_FOCUS_PROJECTED))
        state.transition(false)
        state.transition(true)
        assertEquals(AaProtocol.VIDEO_FOCUS_PROJECTED, state.currentMode())
    }

    @Test fun everyNewSessionStartsProjectedIndependentlyOfOldSession() {
        val oldSession = AaVehicleVideoFocus()
        oldSession.transition(false)
        val newSession = AaVehicleVideoFocus()
        assertEquals(AaProtocol.VIDEO_FOCUS_NATIVE, oldSession.currentMode())
        assertEquals(AaProtocol.VIDEO_FOCUS_PROJECTED, newSession.currentMode())
    }
}
