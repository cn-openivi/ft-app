package app.ft.dr

import app.ft.core.GpsSourceMode
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

/**
 * Owner's three non-negotiable navigation scenarios (2026-11-03):
 * 1) tunnel loses GNSS, vehicle must continue through a turn;
 * 2) urban canyon / mountain reflection introduces 10-100 m GNSS jumps;
 * 3) jammer/spoofer supplies missing, impossible or high-confidence fake GPS.
 *
 * Deterministic source-only simulation with already NORMALIZED, VERIFIED
 * vehicle wheel speed and IMU. This does NOT assert OEM sensor units are
 * known or that live Android Auto / Yandex consumes predicted locations.
 */
class DrRealWorldScenariosTest {
    @Test fun tunnelWithoutSatellitesStillTracksExitTurnFromCarWheelAndYaw() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 2.0))
        // At tunnel entrance satellite observations stop completely.
        repeat(10) { assertNotNull(dr.predict(1.0, 12.0, 0.0, true)) }
        // Driver turns right inside the tunnel. There is NO GNSS correction.
        val duringTurn = dr.predict(1.0, 12.0, PI / 2.0, true)!!
        assertEquals(90.0, duringTurn.headingDegrees, 1e-8)
        repeat(15) { assertNotNull(dr.predict(1.0, 12.0, 0.0, true)) }
        val after = dr.estimate()!!
        assertTrue(after.estimated)
        assertEquals(26.0, after.secondsSinceGnss, 1e-8)
        assertEquals(180.0 + 12.0 / kotlin.math.sqrt(2.0),
            after.eastMetres, 1e-6)
        assertEquals(120.0 + 12.0 / kotlin.math.sqrt(2.0),
            after.northMetres, 1e-6)
        assertTrue(after.horizontalUncertaintyMetres.isFinite())
        // No 7-second waiting and no fallback to phone GPS are involved.
        assertFalse(dr.correctSelectedGnss(GpsSourceMode.CAR,
            GpsSourceMode.PHONE, 10_000.0, 10_000.0, 1.0, true))
    }

    @Test fun canyonJumpsOfTenToOneHundredMetresCannotTeleportEkf() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 1.0))
        repeat(3) { assertNotNull(dr.predict(1.0, 10.0, 0.0, true)) }
        val before = dr.estimate()!!
        // Severe single-frame multipath displacement, with falsely tiny
        // receiver-reported accuracy. Reject it despite "good satellites".
        assertFalse(dr.correctGnss(100.0, 30.0, 0.6, true))
        assertEquals(before.eastMetres, dr.estimate()!!.eastMetres, 0.0)
        assertEquals(before.northMetres, dr.estimate()!!.northMetres, 0.0)
        // A softer reflected coordinate may be incorporated but only via
        // the EKF innovation-weighted update, never as raw absolute position.
        assertTrue(dr.correctGnss(8.0, 30.0, 4.0, true))
        val fused = dr.estimate()!!
        assertTrue(fused.eastMetres > 0.0)
        assertTrue(fused.eastMetres < 8.0)
        assertTrue(fused.horizontalUncertaintyMetres.isFinite())
        assertFalse(fused.estimated)
    }

    @Test fun jammerAndAirportSpoofDoNotOverrideIndependentCarOdometry() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 2.0))
        repeat(180) {
            assertNotNull(dr.predict(1.0, 15.0, 0.0, true))
            // No usable GNSS at all in the jammer region.
            assertFalse(dr.correctGnss(Double.NaN, Double.NaN, 1.0, false))
        }
        val prediction = dr.estimate()!!
        assertEquals(2700.0, prediction.northMetres, 1e-5)
        assertTrue(prediction.estimated)
        // High-confidence but impossible "airport" fix ~20 kilometres away.
        assertFalse(dr.correctSelectedGnss(GpsSourceMode.CAR, GpsSourceMode.CAR,
            18_000.0, 20_000.0, 0.5, true))
        assertEquals(prediction.eastMetres, dr.estimate()!!.eastMetres, 0.0)
        assertEquals(prediction.northMetres, dr.estimate()!!.northMetres, 0.0)
        // Phone fix is not eligible for CAR mode, even if it agrees
        // with the spoofed vehicle GNSS.
        assertFalse(dr.correctSelectedGnss(GpsSourceMode.CAR, GpsSourceMode.PHONE,
            18_000.0, 20_000.0, 0.5, true))
        assertEquals(180.0, dr.estimate()!!.secondsSinceGnss, 0.0)
        // Consistent real GNSS is allowed to correct, not teleport.
        assertTrue(dr.correctSelectedGnss(GpsSourceMode.CAR, GpsSourceMode.CAR,
            0.0, 2700.5, 2.0, true))
        assertFalse(dr.estimate()!!.estimated)
    }

    @Test fun selectedPhoneGpsCanBeRejectedBySameCarImuBasedEkf() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 90.0, 2.0))
        repeat(5) { assertNotNull(dr.predict(1.0, 8.0, 0.0, true)) }
        val before = dr.estimate()!!
        assertFalse(dr.correctSelectedGnss(GpsSourceMode.PHONE, GpsSourceMode.CAR,
            40.0, 0.0, 1.0, true))
        assertFalse(dr.correctSelectedGnss(GpsSourceMode.PHONE, GpsSourceMode.PHONE,
            -100.0, 100.0, 1.0, true))
        assertEquals(before.eastMetres, dr.estimate()!!.eastMetres, 0.0)
        assertTrue(dr.correctSelectedGnss(GpsSourceMode.PHONE, GpsSourceMode.PHONE,
            40.5, 0.5, 2.0, true))
    }

    @Test fun unverifiedVehicleYawCannotClaimTunnelDeadReckoning() {
        val dr = DeadReckoningEkf()
        assertTrue(dr.anchor(0.0, 0.0, 0.0, 2.0))
        assertNull(dr.predict(1.0, 12.0, PI / 2.0, false))
        assertEquals(0.0, dr.estimate()!!.northMetres, 0.0)
        assertFalse(dr.estimate()!!.estimated)
    }
}
