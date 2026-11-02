package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaGearStateTest {
    @Test fun exactOemGearEnumsNeverUseAndroidAutoEnumValues() {
        assertEquals(IccoaGearState.UNKNOWN, IccoaGearState.fromRaw(null))
        assertEquals(IccoaGearState.UNKNOWN, IccoaGearState.fromRaw(0L))
        assertEquals(IccoaGearState.PARK, IccoaGearState.fromRaw(1L))
        assertEquals(IccoaGearState.DRIVE, IccoaGearState.fromRaw(2L))
        assertEquals(IccoaGearState.REVERSE, IccoaGearState.fromRaw(3L))
        assertEquals(IccoaGearState.NEUTRAL, IccoaGearState.fromRaw(4L))
        assertEquals(IccoaGearState.UNKNOWN, IccoaGearState.fromRaw(101L))
        assertEquals(IccoaGearState.UNKNOWN, IccoaGearState.fromRaw(100L))
    }
}
