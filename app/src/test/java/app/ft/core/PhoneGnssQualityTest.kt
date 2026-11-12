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

    @Test fun independentPhoneGpsAloneCannotTrainWheelCoefficient() {
        val estimator = WheelSpeedAutoCalibration(minimumSegments = 3)
        assertTrue(estimator.bindVehicle("verified-vehicle"))
        for (i in 0..2) {
            val nanos = 10_000_000_000L + i * 1_000_000_000L
            // Phone quality alone is never proof of OEM car GNSS fix epoch.
            assertTrue(PhoneGnssQuality.usableForWheelCalibration(
                fix(receivedNanos = nanos), nanos + 200_000_000L))
            estimator.observeWheel(WheelSpeedAutoCalibration.WheelMotion(
                monotonicMs = 10_000L + i * 100L,
                forwardSpeedMps = 20.0,
                yawRateRadPerSecond = 0.0,
                longitudinalAccelerationMps2 = 0.0,
                verifiedWheelAndYawUnits = true,
                verifiedForwardGear = true
            ))
        }
        assertEquals(0, estimator.estimate().acceptedSegments)
        assertFalse(estimator.estimate().confident)
        assertNull(estimator.snapshotForPersistence())
    }

    @Test fun unverifiedIndependentCarGnssIsRejectedEvenWithVerifiedWheelMotion() {
        val estimator = WheelSpeedAutoCalibration(minimumSegments = 3)
        assertTrue(estimator.bindVehicle("verified-vehicle"))
        estimator.observeWheel(WheelSpeedAutoCalibration.WheelMotion(
            monotonicMs = 20_000L,
            forwardSpeedMps = 20.0,
            yawRateRadPerSecond = 0.0,
            longitudinalAccelerationMps2 = 0.0,
            verifiedWheelAndYawUnits = true,
            verifiedForwardGear = true
        ))
        val result = estimator.observeGnss(WheelSpeedAutoCalibration.IndependentGnss(
            fixMonotonicMs = 20_000L,
            latitude = 55.7558, longitude = 37.6172,
            horizontalAccuracyMetres = 1.0,
            independentCarGnssVerified = false,
            monotonicFixEpochVerified = false
        ))
        assertEquals(0, result.acceptedSegments)
        assertFalse(result.confident)
        assertNull(estimator.snapshotForPersistence())
    }
}
