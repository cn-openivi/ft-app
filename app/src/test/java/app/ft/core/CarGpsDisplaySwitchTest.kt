package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CarGpsDisplaySwitchTest {
    private val car = CarGpsDisplaySwitch.Source.CAR
    private val phone = CarGpsDisplaySwitch.Source.PHONE

    @Test fun firstCarFixIsDisplayedWithoutExtraQualityFiltering() {
        val switch = CarGpsDisplaySwitch()
        assertEquals(phone, switch.select(1_000L, null))
        assertEquals(car, switch.select(1_100L, 1_100L))
        assertEquals(car, switch.select(2_000L, 2_000L))
    }

    @Test fun shortGapDoesNotMakeCarPhoneLabelsAlternate() {
        val switch = CarGpsDisplaySwitch()
        assertEquals(car, switch.select(1_000L, 1_000L))
        assertEquals(car, switch.select(4_000L, 1_000L))
        assertEquals(car, switch.select(8_900L, 1_000L))
        assertEquals(phone, switch.select(9_001L, 1_000L))
    }

    @Test fun intermittentCarPacketsAfterPhoneFallbackDoNotFlipImmediately() {
        val switch = CarGpsDisplaySwitch()
        assertEquals(car, switch.select(1_000L, 1_000L))
        assertEquals(phone, switch.select(9_001L, 1_000L))
        assertEquals(phone, switch.select(12_000L, 12_000L))
        assertEquals(phone, switch.select(13_000L, 13_000L))
        assertEquals(phone, switch.select(16_500L, 16_500L)) // recovery reset by >2.5s gap
        assertEquals(phone, switch.select(17_500L, 17_500L))
        assertEquals(phone, switch.select(23_500L, 23_500L)) // continuity broke
    }

    @Test fun sustainedCarRecoveryReclaimsPriorityAfterEightSeconds() {
        val switch = CarGpsDisplaySwitch()
        assertEquals(car, switch.select(1_000L, 1_000L))
        assertEquals(phone, switch.select(9_001L, 1_000L))
        for (tick in 12_000L..19_000L step 1_000L) {
            assertEquals(phone, switch.select(tick, tick))
        }
        assertEquals(car, switch.select(20_000L, 20_000L))
        assertEquals(car, switch.select(21_000L, 21_000L))
    }

    @Test fun cachedGpsTimestampDoesNotCountAsNewFix() {
        val switch = CarGpsDisplaySwitch()
        assertEquals(car, switch.select(1_000L, 1_000L))
        assertEquals(car, switch.select(4_000L, 1_000L))
        assertEquals(phone, switch.select(10_000L, 1_000L))
        assertEquals(phone, switch.select(11_000L, 1_000L))
    }
}
