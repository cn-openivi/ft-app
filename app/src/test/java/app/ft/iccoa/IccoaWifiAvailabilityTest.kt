package app.ft.iccoa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IccoaWifiAvailabilityTest {
    @Test fun disabledRadioIsANamedFailure() {
        assertTrue(IccoaWifiAvailability.radioDisabled(false))
    }

    @Test fun activeOrUnknownRadioDoesNotPretendToBeDisabled() {
        assertFalse(IccoaWifiAvailability.radioDisabled(true))
        assertFalse(IccoaWifiAvailability.radioDisabled(null))
    }
}
