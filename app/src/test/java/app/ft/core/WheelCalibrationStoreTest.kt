package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class WheelCalibrationStoreTest {
    private class MemoryStorage : WheelCalibrationStore.Storage {
        val values = linkedMapOf<String, String>()
        var writeSucceeds = true
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String): Boolean {
            if (!writeSucceeds) return false
            values[key] = value
            return true
        }
    }

    @Test fun differentVerifiedVehiclesNeverShareOneCoefficient() {
        val backing = MemoryStorage()
        val store = WheelCalibrationStore(backing)
        val calibration = WheelSpeedAutoCalibration()
        assertTrue(calibration.bindVehicle("verified-car-A",
            WheelSpeedAutoCalibration.Snapshot("verified-car-A", 1.002)))
        // No dirty complete segment yet: never write just from a saved snapshot.
        assertFalse(store.persistIfPending(calibration, "verified-car-A"))
        assertNull(store.restore("verified-car-A"))
        assertNull(store.restore("verified-car-B"))
        assertFalse(backing.values.keys.any { it.contains("verified-car") })
    }

    @Test fun invalidAndTamperedSavedCoefficientFailClosed() {
        val backing = MemoryStorage()
        val store = WheelCalibrationStore(backing)
        assertNull(store.restore(""))
        assertNull(store.restore("unknown"))
        val key = "wheel-distance.v1." + java.security.MessageDigest
            .getInstance("SHA-256").digest("verified-car-A".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        backing.values[key] = "NaN"
        assertNull(store.restore("verified-car-A"))
        backing.values[key] = "1.90"
        assertNull(store.restore("verified-car-A"))
        backing.values[key] = "1.004"
        assertEquals(1.004, store.restore("verified-car-A")!!.multiplier, 0.0)
        assertNull(store.restore("verified-car-B"))
    }

    @Test fun storageFailureCannotImpersonateSavedCalibration() {
        val backing = MemoryStorage()
        backing.writeSucceeds = false
        val store = WheelCalibrationStore(backing)
        val c = WheelSpeedAutoCalibration()
        assertTrue(c.bindVehicle("verified-car-A"))
        assertFalse(store.persistIfPending(c, "verified-car-A"))
        assertTrue(backing.values.isEmpty())
    }

    @Test fun immutableSnapshotCanCommitWithoutMutatingLiveCalibration() {
        val backing = MemoryStorage()
        val store = WheelCalibrationStore(backing)
        val snapshot = WheelSpeedAutoCalibration.Snapshot("verified-car-A", 1.006)
        assertTrue(store.persistSnapshot(snapshot))
        assertEquals(1.006, store.restore("verified-car-A")!!.multiplier, 0.0)
        assertNull(store.restore("verified-car-B"))
        backing.writeSucceeds = false
        assertFalse(store.persistSnapshot(
            WheelSpeedAutoCalibration.Snapshot("verified-car-A", 1.007)))
        assertEquals(1.006, store.restore("verified-car-A")!!.multiplier, 0.0)
    }

    @Test fun completedThreeSegmentsAreDurableOnlyAfterSuccessfulCommit() {
        val backing = MemoryStorage()
        val store = WheelCalibrationStore(backing)
        val c = WheelSpeedAutoCalibration()
        assertTrue(c.bindVehicle("car-verified-129"))
        for (segment in 1..3) {
            val startMs = segment * 100_000L
            for (step in 0..600) {
                val time = startMs + step * 100L
                c.observeWheel(WheelSpeedAutoCalibration.WheelMotion(
                    monotonicMs = time, forwardSpeedMps = 20.0,
                    yawRateRadPerSecond = 0.0,
                    longitudinalAccelerationMps2 = 0.0,
                    verifiedWheelAndYawUnits = true, verifiedForwardGear = true
                ))
                if (step % 10 == 0) {
                    c.observeGnss(WheelSpeedAutoCalibration.IndependentGnss(
                        fixMonotonicMs = time,
                        latitude = 55.0 + (step * 2.0 * 1.00333) / 111195.080233,
                        longitude = 37.0, horizontalAccuracyMetres = 1.0,
                        independentCarGnssVerified = true,
                        monotonicFixEpochVerified = true
                    ))
                }
            }
        }
        assertTrue(c.estimate().confident)
        assertTrue(c.estimate().pendingPersistence)
        backing.writeSucceeds = false
        assertFalse(store.persistIfPending(c, "car-verified-129"))
        assertTrue(c.estimate().pendingPersistence)
        backing.writeSucceeds = true
        assertTrue(store.persistIfPending(c, "car-verified-129"))
        assertFalse(c.estimate().pendingPersistence)
        val persisted = requireNotNull(store.restore("car-verified-129"))
        assertEquals(c.estimate().multiplier, persisted.multiplier, 0.0)
        assertNull(store.restore("other-verified-car"))
        assertFalse(backing.values.keys.any { it.contains("car-verified-129") })
    }
}
