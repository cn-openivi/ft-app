package app.ft.iccoa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IccoaDirectRecoveryPolicyTest {
    @Test fun shortOemPauseDoesNotImmediatelyReencodeAa() {
        assertFalse(IccoaDirectRecoveryPolicy.shouldRetryPostPause(0L, 10_000L))
        assertFalse(IccoaDirectRecoveryPolicy.shouldRetryPostPause(2_499L, 10_000L))
        assertFalse(IccoaDirectRecoveryPolicy.postPauseExpired(5_000L))
        assertFalse(IccoaDirectRecoveryPolicy.postPauseExpired(6_000L))
        assertFalse(IccoaDirectRecoveryPolicy.postPauseExpired(14_999L))
    }

    @Test fun retryIsBoundedAndThrottledSoAaControlIsNotFlooded() {
        assertTrue(IccoaDirectRecoveryPolicy.shouldRetryPostPause(3_000L, 3_000L))
        assertFalse(IccoaDirectRecoveryPolicy.shouldRetryPostPause(4_000L, 1_000L))
        assertTrue(IccoaDirectRecoveryPolicy.shouldRetryPostPause(6_000L, 3_000L))
        assertTrue(IccoaDirectRecoveryPolicy.shouldRetryPostPause(12_000L, 3_000L))
        assertFalse(IccoaDirectRecoveryPolicy.shouldRetryPostPause(15_000L, 3_000L))
    }

    @Test fun onlyTrueLongNoIdrAfterResumeTriggersEmergencyFallback() {
        assertTrue(IccoaDirectRecoveryPolicy.postPauseExpired(15_000L))
        assertTrue(IccoaDirectRecoveryPolicy.postPauseExpired(16_000L))
        assertFalse(IccoaDirectRecoveryPolicy.postPauseExpired(14_000L))
    }
}
