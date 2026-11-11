package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.VehicleLocation
import app.ft.dr.DrNavigationFix
import app.ft.dr.FtNavigationPosition

/**
 * Owner policy: CAR GNSS -> verified vehicle DR -> UNKNOWN, NEVER auto PHONE.
 * The PHONE source is used only when explicitly selected in Settings.
 *
 * No fixed GNSS loss clock. Select whichever source has the latest genuinely
 * measured OEM WGS84 fix or verified new DR prediction; manual PHONE only.
 * This is a presentation helper, not a GNSS or EKF admission authority.
 */
internal class CarGpsSourceSelector(
    private val onTransition: (Source, Source, Long) -> Unit = { _, _, _ -> }
) {

    enum class Source { ICCOA, DR, PHONE, UNKNOWN }

    data class Reading(
        val source: Source,
        val car: VehicleLocation?,
        val phone: CarInfo.PhoneGpsFix?,
        val dr: DrNavigationFix?,
        val lastPacketAgeMs: Long,
        val speedKmh: Float?
    ) {
        val latitude: Double? get() = when (source) {
            Source.ICCOA -> car?.displayLatitude
            Source.DR -> dr?.latitude
            Source.PHONE -> phone?.latitude
            Source.UNKNOWN -> null
        }
        val longitude: Double? get() = when (source) {
            Source.ICCOA -> car?.displayLongitude
            Source.DR -> dr?.longitude
            Source.PHONE -> phone?.longitude
            Source.UNKNOWN -> null
        }
        val headingDegrees: Float? get() = (when (source) {
            Source.ICCOA -> car?.headingDegrees
            Source.DR -> dr?.headingDegrees?.toFloat()
            Source.PHONE -> phone?.bearingDegrees
            Source.UNKNOWN -> null
        })?.takeIf { it.isFinite() && it in 0f..360f }
        val label: String get() = source.name
    }

    private var lastChosenSource = Source.UNKNOWN

    /**
     * All FT-owned widgets and native apps consume the central FT navigation
     * position; they never independently compare raw GNSS against DR timing.
     */
    fun selectFt(
        position: FtNavigationPosition?,
        phone: CarInfo.PhoneGpsFix?,
        nowMs: Long,
        manualPhoneMode: Boolean = false
    ): Reading = select(
        vehicle = if (position?.source == FtNavigationPosition.Source.RAW_UNFILTERED)
            position.raw else null,
        phone = phone,
        nowMs = nowMs,
        manualPhoneMode = manualPhoneMode,
        drFix = if (position?.source == FtNavigationPosition.Source.FUSED)
            position.fused else null
    )

    /**
     * Manual phone selection is an explicit setting, never derived from loss of
     * ICCOA packets or DR quality. Only the verified GPS_PROVIDER is accepted
     * in manual mode; cell/Wi-Fi/fused approximations are not car GNSS.
     */
    fun select(
        vehicle: VehicleLocation?,
        phone: CarInfo.PhoneGpsFix?,
        nowMs: Long,
        manualPhoneMode: Boolean = false,
        drFix: DrNavigationFix? = null
    ): Reading {
        val candidate = vehicle?.takeIf {
            it.source == "ICCOA" && it.positionAtMs > 0L &&
                it.coordinatesUnit == "ANDROID_LOCATION_WGS84" &&
                it.displayLatitude != null && it.displayLongitude != null &&
                !(it.displayLatitude == 0.0 && it.displayLongitude == 0.0) &&
                it.receivedAtMs > 0L
        }
        val carAgeMs = candidate?.let { nowMs - it.positionAtMs }
        val verifiedDr = drFix?.takeIf { it.eligibleForAa(nowMs) }
        // Single FT authority chooses FUSED; do not compare it to previous
        // raw GPS timestamps or demote to raw after a new packet.
        val newerDr = verifiedDr
        val trustedPhone = phone?.takeIf {
            val callbackAge = nowMs - it.receivedAtMs
            val realFixAgeNanos = it.fixAgeNanosAtReceipt
            it.provider?.equals("gps", ignoreCase = true) == true &&
                it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                it.longitude.isFinite() && it.longitude in -180.0..180.0 &&
                !(it.latitude == 0.0 && it.longitude == 0.0) &&
                (it.horizontalAccuracyMetres == null ||
                    (it.horizontalAccuracyMetres.isFinite() &&
                     it.horizontalAccuracyMetres in 0f..75f)) &&
                callbackAge >= 0L &&
                (realFixAgeNanos == null || realFixAgeNanos >= 0L)
        }
        val chosen = when {
            manualPhoneMode -> Source.PHONE
            newerDr != null -> Source.DR
            candidate != null -> Source.ICCOA
            else -> Source.UNKNOWN
        }
        if (chosen != lastChosenSource) {
            onTransition(lastChosenSource, chosen, nowMs)
            lastChosenSource = chosen
        }
        val carSpeed = candidate?.speedKmh?.takeIf {
            it.isFinite() && it in 0f..300f
        }
        val phoneSpeed = trustedPhone?.speedKmh?.takeIf {
            it.isFinite() && it in 0f..300f
        }
        return Reading(
            source = chosen,
            car = if (chosen == Source.ICCOA) candidate else null,
            phone = if (chosen == Source.PHONE) trustedPhone else null,
            dr = if (chosen == Source.DR) newerDr else null,
            lastPacketAgeMs = when (chosen) {
                Source.ICCOA -> carAgeMs?.coerceAtLeast(0L) ?: 0L
                Source.DR -> newerDr?.let { (nowMs - it.producedAtMs).coerceAtLeast(0L) } ?: 0L
                else -> 0L
            },
            speedKmh = when (chosen) {
                Source.ICCOA -> carSpeed
                Source.PHONE -> phoneSpeed
                Source.DR -> newerDr?.speedKmh
                Source.UNKNOWN -> null
            }
        )
    }
}
