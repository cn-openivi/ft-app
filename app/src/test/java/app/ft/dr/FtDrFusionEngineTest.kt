package app.ft.dr

import app.ft.core.GpsSourceMode
import org.junit.Assert.*
import org.junit.Test

class FtDrFusionEngineTest {
    private fun motion(t: Long, speed: Double = 10.0) =
        FtDrFusionEngine.Motion(
            monotonicMs = t,
            signedWheelSpeedMps = speed,
            yawRateRadiansPerSecond = 0.0,
            wheelUnitsVerified = true,
            yawAxisAndUnitsVerified = true,
            gearDirectionVerified = true
        )

    private fun gnss(t: Long, verified: Boolean = true) =
        FtDrFusionEngine.Gnss(
            latitude = 52.0,
            longitude = 13.0,
            horizontalAccuracyMetres = 2.0,
            courseDegrees = 90.0,
            fixMonotonicMs = t,
            emittedAtMs = t + 100_000L,
            source = GpsSourceMode.CAR,
            genuineCarFix = true,
            accuracyVerified = verified,
            courseVerified = true,
            fixTimeVerified = true
        )

    @Test fun refusesRawGnssAsVerifiedEkfAnchor() {
        var publishes = 0
        val engine = FtDrFusionEngine { _, _, _, _ -> publishes++; true }
        assertFalse(engine.onGnss(gnss(1000L)))
        assertFalse(engine.onMotion(
            motion(1000L).copy(wheelUnitsVerified = false), 101_000L))
        assertFalse(engine.onGnss(gnss(1050L)))
        assertEquals(0, publishes)
    }

    @Test fun provenMotionPlusProvenGnssCreateOneContinuousProducer() {
        val output = mutableListOf<DeadReckoningEkf.Estimate>()
        val engine = FtDrFusionEngine { _, _, estimate, _ ->
            output.add(estimate); true
        }
        assertFalse(engine.onMotion(motion(1000L), 101_000L))
        assertTrue(engine.onGnss(gnss(1000L)))
        assertTrue(engine.onMotion(motion(1100L), 101_100L))
        assertEquals(2, output.size)
        assertTrue(output.last().eastMetres > output.first().eastMetres)
        assertFalse(engine.onGnss(gnss(1000L)))
    }

    @Test fun unverifiedYawCannotMoveCarPosition() {
        val output = mutableListOf<DeadReckoningEkf.Estimate>()
        val engine = FtDrFusionEngine { _, _, estimate, _ ->
            output.add(estimate); true
        }
        engine.onMotion(motion(1000L), 101_000L)
        assertTrue(engine.onGnss(gnss(1000L)))
        assertFalse(engine.onMotion(
            motion(1100L).copy(yawAxisAndUnitsVerified = false),
            101_100L))
        assertEquals(1, output.size)
    }

    @Test fun noAutomaticPhoneSourceSwitch() {
        val engine = FtDrFusionEngine { _, _, _, _ -> true }
        engine.onMotion(motion(1000L), 101_000L)
        assertFalse(engine.onGnss(gnss(1000L).copy(source = GpsSourceMode.PHONE)))
        assertFalse(engine.onGnss(gnss(1100L, verified = false)))
    }

    @Test fun resetRequiresFreshAnchor() {
        var count = 0
        val engine = FtDrFusionEngine { _, _, _, _ -> count++; true }
        engine.onMotion(motion(1000L), 101_000L)
        assertTrue(engine.onGnss(gnss(1000L)))
        engine.reset()
        assertFalse(engine.onMotion(motion(1100L), 101_100L))
        assertEquals(1, count)
    }
}
