package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaGpsTimestampGateTest {
    private val now = 1_794_137_679_967L

    @Test fun yesterdayCachedFixIsNeverPromotedToFreshGps() {
        assertNull(IccoaGpsTimestampGate.measuredAtMs(now - 23L * 60 * 60 * 1000, now))
        assertEquals(now - 66L, IccoaGpsTimestampGate.measuredAtMs(now - 66, now))
    }

    @Test fun phoneAndOemClockSkewMustNotPoisonNextFix() {
        assertEquals(now, IccoaGpsTimestampGate.measuredAtMs(now + 1_050, now))
        assertNull(IccoaGpsTimestampGate.measuredAtMs(now + 2_100, now))
        assertNull(IccoaGpsTimestampGate.measuredAtMs(now - 15_001, now))
        assertEquals(now - 15_000, IccoaGpsTimestampGate.measuredAtMs(now - 15_000, now))
    }

    @Test fun sensorUptimeNanosecondsCannotBeMisrepresentedAsWallClockGps() {
        assertNull(IccoaGpsTimestampGate.measuredAtMs(176_932_179_149L, now))
        assertNull(IccoaGpsTimestampGate.measuredAtMs(0L, now))
    }
}
