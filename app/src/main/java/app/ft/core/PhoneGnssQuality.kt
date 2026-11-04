package app.ft.core

/**
 * Independent handset GNSS reference quality for future wheel-ratio learning.
 *
 * Only raw GPS_PROVIDER fixes with measured speed/position accuracy and a
 * fresh monotonic timestamp can calibrate vehicle odometry. A stale batched
 * location is NOT new simply because Android delivered the callback now.
 *
 * Passing this gate does not prove absence of GNSS spoofing; actual
 * calibration still requires independently checked vehicle units, stable
 * motion and the ratio/outlier gates in WheelSpeedAutoCalibration.
 */
internal object PhoneGnssQuality {
    private const val TWO_SECONDS_NS = 2_000_000_000L

    fun usableForWheelCalibration(
        fix: CarInfo.PhoneGpsFix?,
        monotonicNowNanos: Long
    ): Boolean {
        if (fix == null || fix.provider != "gps" || monotonicNowNanos < 0L)
            return false
        val speed = fix.speedKmh?.toDouble() ?: return false
        val speedAccuracy = fix.speedAccuracyMps?.toDouble() ?: return false
        val horizontalAccuracy = fix.horizontalAccuracyMetres?.toDouble() ?: return false
        val gnssAge = fix.fixAgeNanosAtReceipt ?: return false
        val receivedAt = fix.receivedElapsedRealtimeNanos ?: return false
        if (!speed.isFinite() || speed !in 35.0..140.0 ||
            !speedAccuracy.isFinite() || speedAccuracy !in 0.0..0.6 ||
            !horizontalAccuracy.isFinite() || horizontalAccuracy !in 0.0..15.0 ||
            gnssAge !in 0L..TWO_SECONDS_NS ||
            monotonicNowNanos < receivedAt ||
            monotonicNowNanos - receivedAt > TWO_SECONDS_NS)
            return false
        return true
    }
}
