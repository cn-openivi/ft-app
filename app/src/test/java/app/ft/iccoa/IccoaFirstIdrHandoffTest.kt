package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaFirstIdrHandoffTest {
    private val csd = byteArrayOf(0, 0, 0, 1, 0x67)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x10)
    private val p = byteArrayOf(0, 0, 0, 1, 0x41, 0x10)

    @Test fun retainedFirstIdrDoesNotDependOnAaProducerBufferOwnership() {
        val live = idr.clone()
        val f = IccoaFirstIdrHandoff.capture(live, 123L, csd)!!
        live[4] = 0x41
        assertEquals(0x65, f.bytes[4].toInt() and 255)
        assertEquals(123L, f.timestamp)
        assertTrue(IccoaFirstIdrHandoff.configurationStillCurrent(f, csd))
        assertFalse(IccoaFirstIdrHandoff.configurationStillCurrent(f, csd.clone()))
    }

    @Test fun missingIdrAndOversizedAccessUnitsKeepCompositedMode() {
        assertNull(IccoaFirstIdrHandoff.capture(p, 10L, csd))
        assertNull(IccoaFirstIdrHandoff.capture(byteArrayOf(), 10L, csd))
        assertNull(IccoaFirstIdrHandoff.capture(idr, 10L, byteArrayOf()))
        assertNull(IccoaFirstIdrHandoff.capture(ByteArray(4 * 1024 * 1024 + 1), 10L, csd))
    }
}
