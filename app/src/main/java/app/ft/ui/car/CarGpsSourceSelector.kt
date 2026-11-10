package app.ft.ui.car

import app.ft.core.CarInfo
import app.ft.core.VehicleLocation
import app.ft.dr.DrNavigationFix

/**
 * Owner policy: CAR GNSS -> verified vehicle DR -> UNKNOWN, NEVER auto PHONE.
 * The PHONE source is used only when explicitly selected in Settings.
 *
 * A five-second live-GNSS gap is a loss/status boundary, NOT an artificial
 * hold before forwarding new OEM GPS events to AA. Separate AA subscriptions
 * and TinyEKF admission/uncertainty gates still apply.
 */
internal class CarGpsSourceSelector(
    private val signalLossAfterMs: Long = 5_000L,
    private val onTransition: (Source, Source, Long) -> Unit = { _, _, _ -> }
) {
    init { require(signalLossAfterMs == 5_000L) }

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
                it.displayLatitude != null && it.displayLongitude != null &&
                !(it.displayLatitude == 0.0 && it.displayLongitude == 0.0)
        }
        val carAgeMs = candidate?.let { nowMs - it.positionAtMs }
        val carLive = carAgeMs != null && carAgeMs in 0L until signalLossAfterMs
        val verifiedDr = drFix?.takeIf { it.eligibleForAa(nowMs) }
        val trustedPhone = phone?.takeIf {
            val callbackAge = nowMs - it.receivedAtMs
            val realFixAgeNanos = it.fixAgeNanosAtReceipt
            val remainingNs = if (callbackAge in 0L until signalLossAfterMs)
                (signalLossAfterMs - callbackAge) * 1_000_000L else 0L
            it.provider?.equals("gps", ignoreCase = true) == true &&
                it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                it.longitude.isFinite() && it.longitude in -180.0..180.0 &&
                !(it.latitude == 0.0 && it.longitude == 0.0) &&
                (it.horizontalAccuracyMetres == null ||
                    (it.horizontalAccuracyMetres.isFinite() &&
                     it.horizontalAccuracyMetres in 0f..75f)) &&
                callbackAge in 0L until signalLossAfterMs &&
                (realFixAgeNanos == null ||
                    (realFixAgeNanos >= 0L && realFixAgeNanos <= remainingNs))
        }
        val chosen = when {
            manualPhoneMode -> Source.PHONE
            carLive -> Source.ICCOA
            verifiedDr != null -> Source.DR
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
            dr = if (chosen == Source.DR) verifiedDr else null,
            lastPacketAgeMs = carAgeMs?.coerceAtLeast(0L) ?: 0L,
            speedKmh = when (chosen) {
                Source.ICCOA -> carSpeed
                Source.PHONE -> phoneSpeed
                Source.DR, Source.UNKNOWN -> null
            }
        )
    }
}
