package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class PhoneGnssQualityTest {
    private fun fix(
        receivedNanos: Long = 10_000_000_000L,
        provider: String = "gps",
        speedKmh: Float? = 75.6f,
        speedAccuracy: Float? = 0.25f,
        horizontalAccuracy: Float? = 5f,
        fixAgeNanos: Long? = 200_000_000L
    ) = CarInfo.PhoneGpsFix(
        latitude = 55.7558,
        longitude = 37.6172,
        speedKmh = speedKmh,
        bearingDegrees = 60f,
        receivedAtMs = 1000L,
        provider = provider,
        speedAccuracyMps = speedAccuracy,
        horizontalAccuracyMetres = horizontalAccuracy,
        fixAgeNanosAtReceipt = fixAgeNanos,
        receivedElapsedRealtimeNanos = receivedNanos
    )

    @Test fun freshGpsProviderWithMeasuredAccuracyIsEligible() {
        assertTrue(PhoneGnssQuality.usableForWheelCalibration(
            fix(), 10_500_000_000L))
    }

    @Test fun staleOrBatchedGnssIsNotRejuvenatedByCallback() {
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(fixAgeNanos = 5_000_000_000L), 10_000_000_000L))
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(), 13_000_000_000L))
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(fixAgeNanos = -1L), 10_500_000_000L))
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(), 9_999_000_000L))
    }

    @Test fun providerUncertaintyAndMissingMetadataAreStrict() {
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(provider = "network"), 10_500_000_000L))
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(speedAccuracy = 0.61f), 10_500_000_000L))
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(speedAccuracy = null), 10_500_000_000L))
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(horizontalAccuracy = 16f), 10_500_000_000L))
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(horizontalAccuracy = null), 10_500_000_000L))
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(speedKmh = null), 10_500_000_000L))
        assertFalse(PhoneGnssQuality.usableForWheelCalibration(
            fix(speedKmh = Float.NaN), 10_500_000_000L))
    }

    @Test fun estimatorLearnsOnlyWhenBothGnssAndVehicleUnitsAreVerified() {
        val estimator = WheelSpeedAutoCalibration(minimumSamples = 3)
        for (i in 0..2) {
            val nanos = 10_000_000_000L + i * 1_000_000_000L
            val sample = fix(receivedNanos = nanos)
            val out = estimator.observePhoneGnss(
                monotonicNowNanos = nanos + 200_000_000L,
                vehicleSpeedKmh = 72.0,
                independentPhoneFix = sample,
                yawRateDegreesPerSecond = 0.0,
                accelerationMps2 = 0.0,
                wheelUnitsConfirmedKmh = true,
                motionUnitsVerified = false
            )
            assertEquals(0, out.acceptedSamples)
            assertFalse(out.confident)
        }
        for (i in 0..2) {
            val nanos = 20_000_000_000L + i * 1_000_000_000L
            val out = estimator.observePhoneGnss(
                monotonicNowNanos = nanos + 200_000_000L,
                vehicleSpeedKmh = 72.0,
                independentPhoneFix = fix(receivedNanos = nanos),
                yawRateDegreesPerSecond = 0.0,
                accelerationMps2 = 0.0,
                wheelUnitsConfirmedKmh = true,
                motionUnitsVerified = true
            )
            if (i < 2) assertFalse(out.confident)
        }
        assertTrue(estimator.estimate().confident)
        assertEquals(1.05, estimator.estimate().multiplier, 0.0001)
    }

    @Test fun estimatorRejectsBadGnssEvenIfWheelMotionIsVerified() {
        val c = WheelSpeedAutoCalibration(minimumSamples = 2)
        val result = c.observePhoneGnss(
            monotonicNowNanos = 12_000_000_000L,
            vehicleSpeedKmh = 72.0,
            independentPhoneFix = fix(provider = "network"),
            yawRateDegreesPerSecond = 0.0,
            accelerationMps2 = 0.0,
            wheelUnitsConfirmedKmh = true,
            motionUnitsVerified = true
        )
        assertEquals(0, result.acceptedSamples)
        assertFalse(result.confident)
    }
}
