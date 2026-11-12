package app.ft.projection

import org.junit.Assert.*
import org.junit.Test

class SmartVideoPacerTest {
    @Test fun longDriveCruisesThenWakesOnFirstTouch() {
        var ms = 1_000L
        val p = SmartVideoPacer(true) { ms }
        p.setRequestedFps(30)
        p.onFreshBuffer()
        assertEquals(SmartVideoPacer.State(30, SmartVideoPacer.Mode.ACTIVE), p.state())
        ms += SmartVideoPacer.CRUISE_AFTER_MS
        p.onFreshBuffer() // moving navigation map, not a static image
        assertEquals(SmartVideoPacer.State(15, SmartVideoPacer.Mode.CRUISE), p.state())
        p.onInput()
        assertEquals(SmartVideoPacer.State(30, SmartVideoPacer.Mode.ACTIVE), p.state())
    }

    @Test fun staticRequiresAbsenceOfNewBuffersNotJustAbsenceOfTouches() {
        var ms = 1_000L
        val p = SmartVideoPacer(true) { ms }
        p.setRequestedFps(30)
        ms += 45_000L
        p.onFreshBuffer()
        assertEquals(15, p.state().fps)
        ms += 4_999L
        assertEquals(15, p.state().fps)
        ms++
        assertEquals(SmartVideoPacer.State(5, SmartVideoPacer.Mode.STATIC), p.state())
        p.onFreshBuffer()
        assertEquals(15, p.state().fps)
    }

    @Test fun disabledModePreservesNegotiatedFpsAndThermalLimit() {
        var ms = 1_000L
        val p = SmartVideoPacer(false) { ms }
        p.setRequestedFps(24)
        ms += 1_000_000L
        assertEquals(SmartVideoPacer.State(24, SmartVideoPacer.Mode.OFF), p.state())
        p.setThermalCeiling(20)
        assertEquals(SmartVideoPacer.State(20, SmartVideoPacer.Mode.THERMAL), p.state())
        p.setThermalCeiling(0)
        assertEquals(24, p.state().fps)
    }

    @Test fun thermalAndNegotiatedCapsNeverExceeded() {
        val p = SmartVideoPacer(true) { 1_000L }
        p.setRequestedFps(12)
        p.setThermalCeiling(24)
        assertEquals(12, p.state().fps)
        p.setThermalCeiling(8)
        assertEquals(8, p.state().fps)
    }

    @Test fun urgentSignalOnlyOnceAndOnlyForFreshBufferWithinWindow() {
        var ns = 1_000_000_000L
        val urgent = UrgentFreshFrame { ns }
        assertFalse(urgent.takeOnFreshBuffer())
        urgent.arm(600)
        ns += 599_000_000L
        assertTrue(urgent.takeOnFreshBuffer())
        assertFalse(urgent.takeOnFreshBuffer())
        urgent.arm(600)
        ns += 601_000_000L
        assertFalse(urgent.takeOnFreshBuffer())
        urgent.arm()
        urgent.cancel()
        assertFalse(urgent.takeOnFreshBuffer())
    }
}
