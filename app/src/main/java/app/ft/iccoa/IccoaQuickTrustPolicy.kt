package app.ft.iccoa

/**
 * Only one owner-approved car is currently supported by the encrypted trust store.
 * Bluetooth names may be blank and addresses may rotate. Neither is a stable
 * cryptographic identity; the saved OEM server EC key verifies the selected car.
 */
internal object IccoaQuickTrustPolicy {
    const val PRIMARY_TRUST_SCOPE = "owner-approved-primary-car-v1"

    /**
     * Receiver serviceData token is a lookup hint, NEVER proof of trust.
     * The actual 64-byte OEM EC public key stays pinned under Android Keystore;
     * a cloned/rotating BLE advertisement cannot pass signed QUICK Auth.
     */
    fun scopeForReceiverToken(token: String?): String? {
        val normalized = token?.lowercase(java.util.Locale.ROOT) ?: return null
        if (!normalized.matches(Regex("[0-9a-f]{32}"))) return null
        return "receiver-token-v1:$normalized"
    }

    /**
     * An automatically selected peer must match the remembered car metadata.
     * A manual selection may probe QUICK against the pinned server key; a
     * different receiver cannot authenticate without the matching private key.
     */
    fun mayAttemptQuick(remembered: Boolean, explicitlySelected: Boolean,
                        forceCurrentPin: Boolean): Boolean =
        !forceCurrentPin && (remembered || explicitlySelected)
}
