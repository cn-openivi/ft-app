package app.ft.dr

import app.ft.core.GpsSourceMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FtDrRuntimeNoIdentityTest {
    @Before fun reset() { FtDrRuntime.reset() }

    private fun gps(t: Long) = FtDrFusionEngine.Gnss(
        latitude = 52.0, longitude = 13.0,
        horizontalAccuracyMetres = 2.0, courseDegrees = 90.0,
        fixMonotonicMs = t, emittedAtMs = t + 100_000L,
        source = GpsSourceMode.CAR,
        genuineCarFix = true, accuracyVerified = true,
        courseVerified = true, fixTimeVerified = true
    )

    private fun motion(t: Long, trustedYaw: Boolean = true) = FtDrFusionEngine.Motion(
        monotonicMs = t, signedWheelSpeedMps = 10.0,
        yawRateRadiansPerSecond = 0.0,
        wheelUnitsVerified = true,
        yawAxisAndUnitsVerified = trustedYaw,
        gearDirectionVerified = true
    )

    @Test fun reliableGnssAdmitsWithoutCarIdOrMotion() {
        assertTrue(FtDrRuntime.acceptVerifiedGnss(gps(1000L)))
        assertNull(DrNavigationTelemetry.current.value)
    }

    @Test fun verifiedDrPredictsWithoutUnnecessaryCarIdentityGate() {
        assertTrue(FtDrRuntime.acceptVerifiedGnss(gps(1000L)))
        assertFalse(FtDrRuntime.acceptVerifiedMotion(motion(1000L), 101_000L))
        assertTrue(FtDrRuntime.acceptVerifiedMotion(motion(1100L), 101_100L))
        assertNotNull(DrNavigationTelemetry.current.value)
    }

    @Test fun invalidYawStillRejectsWithoutCarIdentity() {
        assertTrue(FtDrRuntime.acceptVerifiedGnss(gps(1000L)))
        assertFalse(FtDrRuntime.acceptVerifiedMotion(
            motion(1000L, trustedYaw = false), 101_000L))
        assertNull(DrNavigationTelemetry.current.value)
    }
}
