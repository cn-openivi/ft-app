package app.ft.projection

/**
 * Touch calibration follows the verified OEM EC public-key fingerprint, never
 * the receiver name, rotating BLE MAC, untrusted serviceData or screen geometry.
 */
internal object IccoaCalibrationScope {
    @Volatile private var verifiedId: String? = null
    @Volatile private var legacyPrimary: Boolean = false

    fun activate(receiverId: String, verifiedLegacyPrimary: Boolean) {
        require(receiverId.matches(Regex("[0-9a-f]{64}")))
        verifiedId = receiverId
        legacyPrimary = verifiedLegacyPrimary
    }

    fun key(base: String): String? = verifiedId?.let { "verified_$it:$base" }

    fun mayUseLegacy(): Boolean = verifiedId != null && legacyPrimary

    fun clear() {
        verifiedId = null
        legacyPrimary = false
    }
}
