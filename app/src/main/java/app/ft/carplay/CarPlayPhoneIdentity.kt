package app.ft.carplay

import java.util.Locale

/**
 * The phone-side CarPlay identity must refer to the SAME physical Android Bluetooth adapter
 * already paired with Toyota for HFP/A2DP/AVRCP.
 *
 * This value is read from the real phone by a separately authorized platform bridge; it must
 * never be generated, replaced by the head unit's address, or taken from Android's masked
 * 02:00:00:00:00:00 placeholder. This class neither changes Bluetooth state nor advertises
 * CarPlay on its own.
 *
 * OEM FAW Carplay.apk compares the active CarPlay device address to its HFP device address.
 * A match here is only an identity precondition, NOT proof of an active HFP voice/audio route.
 */
internal class CarPlayPhoneIdentity private constructor(
    val bluetoothAddress: String
) {
    enum class HfpDeviceMatch {
        SAME_DEVICE,
        DIFFERENT_DEVICE,
        NOT_OBSERVED
    }

    fun compareHfpDevice(observedAddress: String?): HfpDeviceMatch {
        val normalized = normalize(observedAddress) ?: return HfpDeviceMatch.NOT_OBSERVED
        return if (normalized == bluetoothAddress) {
            HfpDeviceMatch.SAME_DEVICE
        } else {
            HfpDeviceMatch.DIFFERENT_DEVICE
        }
    }

    companion object {
        private val MAC = Regex("[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}")
        private const val MASKED_ANDROID_ADDRESS = "02:00:00:00:00:00"
        private const val UNSPECIFIED_ADDRESS = "00:00:00:00:00:00"

        /** Return null (fail closed) until a real local, unicast adapter address is available. */
        fun fromRealLocalAddress(address: String?): CarPlayPhoneIdentity? =
            normalize(address)?.let(::CarPlayPhoneIdentity)

        private fun normalize(address: String?): String? {
            if (address == null || !MAC.matches(address)) return null
            val normalized = address.uppercase(Locale.ROOT)
            if (normalized == MASKED_ANDROID_ADDRESS || normalized == UNSPECIFIED_ADDRESS) return null
            // Bluetooth BR/EDR public/random device identities are individual, not group addresses.
            if ((normalized.substring(0, 2).toInt(16) and 1) != 0) return null
            return normalized
        }
    }
}
