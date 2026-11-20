package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class WheelCalibrationSessionTest {
    private fun gps(at: Long, northMetres: Double, accurate: Boolean = true,
                    bearing: Double = 0.0) = WheelCalibrationSession.Gnss(
        fixMonotonicMs = at, receivedMonotonicMs = at + 120L,
        latitude = 55.0 + northMetres / 111_195.08,
        longitude = 37.0,
        accuracyMetres = if (accurate) 1.0 else 15.0,
        speedMps = 20.0, speedAccuracyMps = 0.25,
        bearingDegrees = bearing, provider = "gps"
    )

    private fun drive(s: WheelCalibrationSession, kilometres: Int, ratio: Double = 1.01,
                      invalidAtSecond: Int? = null): WheelCalibrationSession.Status {
        val count = kilometres * 500 + 25 // 20m/s, 10Hz, 2/5 km plus margin
        for (step in 0..count) {
            val at = 1_000L + step * 100L
            s.onGear(72.0, 2L, at)
            if (step % 10 == 0) {
                val second = step / 10
                val actualNorth = second * 20.0 * ratio
                val state = s.onGnss(gps(at, actualNorth, invalidAtSecond != second))
                if (state.phase == WheelCalibrationSession.Phase.SAVING) return state
            }
        }
        return s.status
    }

    @Test fun twoKilometresStopsExactlyOnceAndLocksAfterSave() {
        val s = WheelCalibrationSession()
        assertEquals(2_000, s.begin(2, 1_000L).targetMetres)
        val result = drive(s, 2)
        assertEquals(WheelCalibrationSession.Phase.SAVING, result.phase)
        assertEquals(2_000, result.measuredMetres)
        assertEquals(1.01, result.multiplier!!, 0.003)
        s.finishSave(true)
        assertEquals(WheelCalibrationSession.Phase.COMPLETE, s.status.phase)
        s.onGear(100.0, 2L, 500_000L)
        s.onGnss(gps(500_000L, 10_000.0))
        assertEquals(result.multiplier, s.status.multiplier)
    }

    @Test fun fiveKilometresUsesTheSameAutomaticCompletion() {
        val s = WheelCalibrationSession()
        s.begin(5, 1_000L)
        val result = drive(s, 5, ratio = 0.995)
        assertEquals(WheelCalibrationSession.Phase.SAVING, result.phase)
        assertEquals(5_000, result.targetMetres)
        assertEquals(0.995, result.multiplier!!, 0.003)
    }

    @Test fun inaccurateGpsBreaksSegmentWithoutEverSavingEarly() {
        val s = WheelCalibrationSession()
        s.begin(2, 1_000L)
        for (step in 0..400) {
            val at = 1_000L + step * 100L
            s.onGear(72.0, 2L, at)
            if (step % 10 == 0) s.onGnss(gps(at, step * 2.0,
                accurate = step != 100))
        }
        assertNotEquals(WheelCalibrationSession.Phase.SAVING, s.status.phase)
        assertTrue(s.status.measuredMetres < 2_000)
    }

    @Test fun turnoutAndTimeoutCannotProduceCoefficient() {
        val s = WheelCalibrationSession()
        s.begin(2, 1_000L)
        s.onGear(72.0, 2L, 1_000L)
        s.onGnss(gps(1_000L, 0.0))
        s.onGear(72.0, 2L, 2_000L)
        val turned = s.onGnss(gps(2_000L, 20.0, bearing = 30.0))
        assertEquals(WheelCalibrationSession.Phase.WAITING, turned.phase)
        val timed = s.timeCheck(1_000L + 20 * 60_000L)
        assertEquals(WheelCalibrationSession.Phase.TIMED_OUT, timed.phase)
        assertNull(timed.multiplier)
    }
}
