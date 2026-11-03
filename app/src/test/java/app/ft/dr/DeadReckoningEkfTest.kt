package app.ft.dr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class DeadReckoningEkfTest {
    @Test fun noEstimatedLocationBeforeTrueAnchorAndVerifiedOemSensors() {
        val dr = DeadReckoningEkf()
        assertNull(dr.estimate())
        assertNull(dr.predict(1.0, 10.0, 0.0, wheelAndGyroVerified = true))
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 3.0))
        assertNull(dr.predict(1.0, 10.0, 0.0, wheelAndGyroVerified = false))
        assertFalse(dr.estimate()!!.estimated)
        assertEquals(0.0, dr.estimate()!!.northMetres, 0.0)
    }

    @Test fun northboundCarMovesByCalibratedWheelDistanceOnly() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(100.0, 200.0, 0.0, 3.0))
        repeat(10) {
            val prediction = dr.predict(1.0, 10.0, 0.0, wheelAndGyroVerified = true)
            assertNotNull(prediction)
            assertTrue(prediction!!.estimated)
        }
        val position = dr.estimate()!!
        assertEquals(100.0, position.eastMetres, 1e-8)
        assertEquals(300.0, position.northMetres, 1e-8)
        assertEquals(0.0, position.headingDegrees, 1e-8)
        assertEquals(10.0, position.secondsSinceGnss, 1e-10)
    }

    @Test fun ninetyDegreeTurnUsesYawAndMidpointIntegration() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 0.0, 1.0)
        val prediction = dr.predict(
            dtSeconds = 1.0,
            calibratedSpeedMetresPerSecond = 10.0,
            vehicleYawRateRadiansPerSecond = PI / 2.0,
            wheelAndGyroVerified = true
        )!!
        assertEquals(90.0, prediction.headingDegrees, 1e-8)
        assertEquals(7.071067811865475, prediction.eastMetres, 1e-8)
        assertEquals(7.071067811865475, prediction.northMetres, 1e-8)
    }

    @Test fun trustedGnssCorrectionShrinksEstimateErrorWithoutTeleporting() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 0.0, 3.0)
        dr.predict(1.0, 10.0, 0.0, true)
        val before = dr.estimate()!!
        assertTrue(dr.correctGnss(0.5, 10.5, 1.0, genuineGnss = true))
        val after = dr.estimate()!!
        assertFalse(after.estimated)
        assertEquals(0.0, after.secondsSinceGnss, 0.0)
        assertTrue(after.eastMetres > before.eastMetres)
        assertTrue(after.northMetres > before.northMetres)
        assertTrue(after.horizontalUncertaintyMetres <
            before.horizontalUncertaintyMetres)
        assertFalse(dr.correctGnss(10_000.0, -10_000.0, 1.0, true))
        assertEquals(after.eastMetres, dr.estimate()!!.eastMetres, 0.0)
    }

    @Test fun invalidRawSensorUnitsCannotAdvanceDeadReckoning() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 180.0, 4.0)
        assertNull(dr.predict(1.0, 40.0, 0.0, false))
        assertNull(dr.predict(Double.NaN, 20.0, 0.0, true))
        assertNull(dr.predict(5.0, 20.0, 0.0, true))
        assertNull(dr.predict(1.0, Double.POSITIVE_INFINITY, 0.0, true))
        assertNull(dr.predict(1.0, 10.0, 100.0, true))
        assertEquals(0.0, dr.estimate()!!.secondsSinceGnss, 0.0)
    }

    @Test fun noSyntheticLocationAfterTimeSafetyLimit() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 0.0, 1.0)
        repeat(120) { dr.predict(1.0, 0.0, 0.0, true) }
        assertNull(dr.predict(1.0, 0.0, 0.0, true))
        assertEquals(120.0, dr.estimate()!!.secondsSinceGnss, 1e-9)
        dr.reset()
        assertNull(dr.estimate())
    }

    @Test fun noCorrectionFromPredictedOrUnverifiedGps() {
        val dr = DeadReckoningEkf()
        dr.anchor(0.0, 0.0, 30.0, 3.0)
        dr.predict(1.0, 5.0, 0.0, true)
        val old = dr.estimate()!!
        assertFalse(dr.correctGnss(5.0, 5.0, 2.0, genuineGnss = false))
        assertEquals(old.eastMetres, dr.estimate()!!.eastMetres, 0.0)
        assertTrue(abs(dr.estimate()!!.headingDegrees - 30.0) < 1e-6)
    }
}
