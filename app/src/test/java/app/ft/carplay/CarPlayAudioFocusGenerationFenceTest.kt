package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayAudioFocusGenerationFenceTest {
    @Test fun previousRequestCannotReclaimFocusAfterNativeCallBegins() {
        val gate = CarPlayAudioFocusGenerationFence()
        val old = gate.beginRequest()
        var focus = -1
        assertTrue(gate.accept(old, 1) { focus = it })
        gate.invalidate()
        focus = -1
        assertFalse(gate.accept(old, 1) { focus = it })
        assertEquals(-1, focus)
    }

    @Test fun newAudioSourceRejectsOlderCallbackButAcceptsItsOwn() {
        val gate = CarPlayAudioFocusGenerationFence()
        val prior = gate.beginRequest()
        val current = gate.beginRequest()
        var focus = -1
        assertFalse(gate.accept(prior, 1) { focus = it })
        assertTrue(gate.accept(current, -2) { focus = it })
        assertEquals(-2,focus)
    }

    @Test fun releaseInvalidatesLateLossCallbacksBeforeOtherBackendStarts() {
        val gate = CarPlayAudioFocusGenerationFence()
        val one = gate.beginRequest()
        gate.invalidate()
        var called = false
        assertFalse(gate.accept(one, -1) { called = true })
        assertFalse(called)
        val next = gate.beginRequest()
        assertTrue(gate.accept(next, 1) { called = true })
        assertTrue(called)
    }
}
