package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayAirPlayNsdResolutionFenceTest {
    @Test fun lateResolutionAfterServiceLostIsNeverAnEndpoint() {
        val f = CarPlayAirPlayNsdResolutionFence()
        assertTrue(f.begin(1))
        val old = f.beginResolution(1, "Toyota AirPlay")!!
        assertTrue(f.serviceLost(1, "Toyota AirPlay"))
        assertFalse(f.complete(old, "Toyota AirPlay"))
        val current = f.beginResolution(1, "Toyota AirPlay")!!
        assertTrue(f.complete(current, "Toyota AirPlay"))
        assertFalse(f.complete(current, "Toyota AirPlay"))
        assertFalse(current.toString().contains("Toyota"))
    }

    @Test fun afterSessionRestartOnlyNewGenerationCanComplete() {
        val f = CarPlayAirPlayNsdResolutionFence()
        f.begin(3)
        val old = f.beginResolution(3, "old")!!
        assertTrue(f.begin(4))
        assertFalse(f.complete(old, "old"))
        assertNull(f.beginResolution(3, "old"))
        val current = f.beginResolution(4, "current")!!
        assertFalse(f.complete(current, "wrong"))
        assertFalse(f.complete(current, "current"))
        val replacement = f.beginResolution(4, "current")!!
        assertTrue(f.complete(replacement, "current"))
        f.close()
        assertNull(f.beginResolution(4, "current"))
    }

    @Test fun noDuplicateConcurrentResolverAndNoForgedService() {
        val f = CarPlayAirPlayNsdResolutionFence()
        assertFalse(f.begin(0))
        assertTrue(f.begin(7))
        assertNull(f.beginResolution(7, ""))
        assertNull(f.beginResolution(8, "wrong"))
        val first = f.beginResolution(7, "A")!!
        assertNull(f.beginResolution(7, "B"))
        assertFalse(f.serviceLost(6, "A"))
        assertFalse(f.complete(first, "B"))
        assertTrue(f.complete(first, "A"))
        assertNotNull(f.beginResolution(7, "B"))
    }
}
