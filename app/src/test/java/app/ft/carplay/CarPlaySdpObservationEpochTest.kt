package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlaySdpObservationEpochTest {
    @Test fun staleToyotaSdpCallbackCannotResolveNewSelectedToyota() {
        val f = CarPlaySdpObservationEpoch()
        val first = f.begin()
        assertTrue(f.accepts(first))
        val second = f.begin()
        assertFalse(f.accepts(first))
        assertFalse(f.complete(first))
        assertTrue(f.accepts(second))
        assertTrue(f.complete(second))
        assertFalse(f.complete(second))
    }

    @Test fun oldTimeoutCannotCloseOrCompleteNewRequest() {
        val f = CarPlaySdpObservationEpoch()
        val original = f.begin()
        f.close()
        assertFalse(f.complete(original))
        val next = f.begin()
        assertFalse(f.accepts(original))
        assertTrue(f.complete(next))
        assertFalse(f.accepts(next))
    }

    @Test fun closeAndDuplicateBroadcastAreConsumedOnlyOnce() {
        val f = CarPlaySdpObservationEpoch()
        assertFalse(f.complete(0L))
        val run = f.begin()
        assertTrue(f.complete(run))
        assertFalse(f.complete(run))
        f.close()
        assertFalse(f.accepts(run))
    }
}
