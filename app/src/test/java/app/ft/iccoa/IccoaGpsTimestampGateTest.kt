package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaGpsTimestampGateTest {
    @Test fun newIccoaReceiveEventUsesLocalArrivalNotOemClockAge() {
        val now = 1_794_137_679_967L
        assertEquals(now, IccoaGpsTimestampGate.observedAtReceiveMs(now))
        // OEM packet timestamp remains raw metadata; old/future wall-clock
        // timestamps no longer reject a genuinely newly received packet.
    }

    @Test fun invalidLocalReceiptCannotBeAPositionEvent() {
        assertNull(IccoaGpsTimestampGate.observedAtReceiveMs(0L))
        assertNull(IccoaGpsTimestampGate.observedAtReceiveMs(-1L))
    }
}
