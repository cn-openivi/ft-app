package app.ft.dr

import app.ft.core.GpsSourceMode

/**
 * The single process-wide producer for verified position fusion.
 *
 * Only an OEM adapter with independently demonstrated units, axes,
 * vehicle identity and fix-time may call acceptVerified... methods.
 * Existing raw ICCOA GearInfo/IMU/GPS packets do not pass these gates.
 * FT native consumers and Android Auto continue to consume FtPositionTelemetry,
 * never this engine or a separate raw position provider.
 */
internal object FtDrRuntime {
    private val fusion = FtDrFusionEngine { latitude, longitude, estimate, emittedAtMs ->
        DrNavigationTelemetry.publishFromVerifiedEkf(
            referenceLatitude = latitude,
            referenceLongitude = longitude,
            estimate = estimate,
            gpsSourceMode = GpsSourceMode.CAR,
            wheelSpeedAndYawVerified = true,
            producedAtMs = emittedAtMs
        )
    }

    @Synchronized
    fun acceptVerifiedMotion(motion: FtDrFusionEngine.Motion, emittedAtMs: Long): Boolean =
        fusion.onMotion(motion, emittedAtMs)

    @Synchronized
    fun acceptVerifiedGnss(fix: FtDrFusionEngine.Gnss): Boolean =
        fusion.onGnss(fix)

    @Synchronized
    fun reset() {
        fusion.reset()
        DrNavigationTelemetry.clear()
    }
}
