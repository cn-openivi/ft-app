package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorTouchDimWindowTest {
    @Test fun startsDimWithoutArmingAnInactivityTimer() {
        val window = MirrorTouchDimWindow()
        window.start()
        assertNull(window.remainingMs(0L))
        assertFalse(window.isDue(60_000L))
    }

    @Test fun firstPhoneTouchLightsThenRedimsAfter30Seconds() {
        val window = MirrorTouchDimWindow()
        window.start()
        window.touched(100L)
        assertEquals(30_000L, window.remainingMs(100L))
        assertFalse(window.isDue(30_099L))
        assertTrue(window.isDue(30_100L))
        window.darkened()
        assertFalse(window.isDue(60_000L))
        assertNull(window.remainingMs(60_000L))
    }

    @Test fun repeatedPhysicalTouchRestartsInactivityTimer() {
        val window = MirrorTouchDimWindow()
        window.start()
        window.touched(100L)
        window.touched(29_500L)
        assertFalse(window.isDue(30_100L))
        assertEquals(29_400L, window.remainingMs(30_100L))
        assertTrue(window.isDue(59_500L))
    }

    @Test fun stoppingMirrorCancelsRedimAndIgnoresFurtherTouch() {
        val window = MirrorTouchDimWindow()
        window.start()
        window.touched(100L)
        window.stop()
        window.touched(200L)
        assertFalse(window.isDue(50_000L))
        assertNull(window.remainingMs(50_000L))
    }
}
