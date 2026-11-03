package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.VehicleLocation
import org.junit.Assert.*
import org.junit.Test

class CarWidgetReadingsTest {
    private fun phone(t: Long) = CarInfo.PhoneGpsFix(
        latitude = 55.55, longitude = 37.56, speedKmh = 26f,
        bearingDegrees = 231f, receivedAtMs = t
    )

    @Test fun phoneSpeedIsUsedDuringFallback() {
        val source = CarGpsSourceSelector()
        val choice = source.select(null, phone(10_000L), 10_000L)
        assertEquals("PHONE", CarWidgetReadings.sourceLabel(choice))
        assertEquals(26f, CarWidgetReadings.speedKmh(choice, false)!!, 0f)
    }

    @Test fun carIsPrimaryAfterRecoveryAndHoldingDoesNotMarkStaleZero() {
        val source = CarGpsSourceSelector()
        for (t in 10_000L..17_000L step 1_000L)
            source.select(VehicleLocation("ICCOA", t,
                latitude=55.55207,longitude=37.55462,speedKmh=25.2f),
                phone(t),t)
        val selected=source.select(null,phone(21_000L),21_000L)
        assertEquals(CarGpsSourceSelector.Source.ICCOA,selected.source)
        assertEquals("ICCOA · HOLD 4s",CarWidgetReadings.sourceLabel(selected))
        assertEquals(25.2f,CarWidgetReadings.speedKmh(selected,false)!!,0f)
    }

    @Test fun oneSecondUiTickLagCannotMakeIncomingFixLookOld() {
        val car = VehicleLocation("ICCOA", 10_900L, positionAtMs=10_900L,
            latitude=55.55207,longitude=37.55462)
        val current = CarWidgetReadings.displayNow(10_000L,car,phone(10_800L))
        assertEquals(10_900L,current)
    }

    @Test fun untrustedPhoneSpeedIsNotDisplayedAsRealMeasurement() {
        val selector = CarGpsSourceSelector()
        val invalid=phone(10_000L).copy(speedKmh=Float.NaN)
        val reading=selector.select(null,invalid,10_000L)
        assertNull(CarWidgetReadings.speedKmh(reading,false))
        assertEquals(60f,CarWidgetReadings.speedKmh(reading,true)!!,0f)
    }

    @Test fun lostGpsAndUnavailablePhoneDoNotInventCoordinates() {
        val selector = CarGpsSourceSelector()
        val choice = selector.select(null,null,10_000L)
        assertNull(choice.latitude)
        assertNull(choice.longitude)
        assertNull(CarWidgetReadings.speedKmh(choice,false))
    }
}
