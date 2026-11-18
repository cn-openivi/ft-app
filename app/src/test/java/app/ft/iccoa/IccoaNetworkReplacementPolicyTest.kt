package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaNetworkReplacementPolicyTest {
    @Test fun preAuthReplacementAtoBAllowsFreshNetworkBoundSocket() {
        assertFalse(IccoaNetworkReplacementPolicy.mustRestartSession(
            networkChanged = true, authenticated = false))
    }

    @Test fun afterAuthReplacementForcesFullRtspRtpUibcCleanup() {
        assertTrue(IccoaNetworkReplacementPolicy.mustRestartSession(
            networkChanged = true, authenticated = true))
    }

    @Test fun repeatedLinkPropertiesForSameNetworkDoNotReconnect() {
        assertFalse(IccoaNetworkReplacementPolicy.mustRestartSession(
            networkChanged = false, authenticated = true))
    }
}
