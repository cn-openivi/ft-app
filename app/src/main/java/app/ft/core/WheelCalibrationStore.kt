package app.ft.core

import java.security.MessageDigest

/**
 * One durable wheel-distance coefficient per independently authenticated vehicle.
 * A BLE advertisement, a rotating MAC, a model name and FT's virtual AA HU ID
 * are not proof of the physical vehicle's identity.
 *
 * Disk writes are only made for an accepted complete calibration segment,
 * never in the 5–10 Hz sensor hot path. Existing car speedometer is unchanged.
 */
internal class WheelCalibrationStore(private val storage: Storage) {
    interface Storage {
        fun read(key: String): String?
        /** Must return true only after the value was durably committed. */
        fun write(key: String, value: String): Boolean
    }

    fun restore(verifiedVehicleId: String): WheelSpeedAutoCalibration.Snapshot? {
        val key = keyFor(verifiedVehicleId) ?: return null
        val value = storage.read(key)?.toDoubleOrNull()
        if (value == null || !value.isFinite() || value !in 0.90..1.10) return null
        return WheelSpeedAutoCalibration.Snapshot(verifiedVehicleId, value)
    }

    /**
     * Persist an immutable already-confirmed calibration snapshot from an IO
     * dispatcher. Never hold the FT sensor lock during a disk commit.
     */
    fun persistSnapshot(snapshot: WheelSpeedAutoCalibration.Snapshot): Boolean {
        val key = keyFor(snapshot.vehicleId) ?: return false
        if (!snapshot.multiplier.isFinite() ||
            snapshot.multiplier !in 0.90..1.10) return false
        return storage.write(key, snapshot.multiplier.toString())
    }

    fun persistIfPending(
        calibration: WheelSpeedAutoCalibration,
        verifiedVehicleId: String
    ): Boolean {
        val pending = calibration.snapshotForPersistence() ?: return false
        if (pending.vehicleId != verifiedVehicleId) return false
        if (!persistSnapshot(pending)) return false
        return calibration.confirmPersisted(pending)
    }

    private fun keyFor(vehicleId: String): String? {
        if (vehicleId.isBlank() || vehicleId.length > 256) return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(vehicleId.toByteArray(Charsets.UTF_8))
        return "wheel-distance.v1." + bytes.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }
}
