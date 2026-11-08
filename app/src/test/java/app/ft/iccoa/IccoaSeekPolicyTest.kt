package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaSeekPolicyTest {
    @Test fun waitingNeverStopsAfterThirdOrThousandthScan() {
        val policy = IccoaSeekPolicy()
        assertEquals(5_000L, policy.nextDelayMs())
        assertEquals(10_000L, policy.nextDelayMs())
        repeat(1000) { assertEquals(25_000L, policy.nextDelayMs()) }
        policy.reset()
        assertEquals(5_000L, policy.nextDelayMs())
    }

    @Test fun ownerSwitchHasStrictSearchLifecycle() {
        assertTrue(IccoaSeekPolicy.eligible(true, true, false, false, false))
        assertFalse(IccoaSeekPolicy.eligible(false, true, false, false, false))
        assertFalse(IccoaSeekPolicy.eligible(true, false, false, false, false))
        assertFalse(IccoaSeekPolicy.eligible(true, true, true, false, false))
        assertFalse(IccoaSeekPolicy.eligible(true, true, false, true, false))
        assertFalse(IccoaSeekPolicy.eligible(true, true, false, false, true))
    }

    @Test fun transportAuthenticationRetriesRemainSeparatelyBounded() {
        val guard = IccoaDropRecovery()
        assertEquals(1, guard.onFailure(true, true, true))
        assertEquals(2, guard.onFailure(false, true, true))
        assertEquals(3, guard.onFailure(false, true, true))
        assertNull(guard.onFailure(false, true, true))
        assertFalse(guard.active)
        // A new discovery window is still available, without recycling a PIN.
        assertEquals(5_000L, IccoaSeekPolicy().nextDelayMs())
        assertNull(guard.onFailure(false, true, false))
    }
}
