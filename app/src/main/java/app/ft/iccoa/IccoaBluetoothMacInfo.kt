package app.ft.iccoa

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.ft.core.ProtoWriter
import java.security.MessageDigest
import java.util.Locale

/**
 * Exact FAW XA60 OEM ucar.proto: CONTROL(category=1, method=22).
 *
 * BluetoothMacInfo { OPType op_type=1 (ADD=0, DELETE=1), string bluetooth_mac=2 }.
 * The car stores field 2 UNCHANGED under the authenticated ICCOA phoneId, then
 * compares it with SHA-256 of the HU-observed raw Classic Bluetooth MAC string.
 * This is NOT the ICCOA synthetic Wi-Fi/P2P MAC and NOT a Bluetooth bond/link key.
 */
internal object IccoaBluetoothMacInfo {
    const val CONTROL_METHOD = 22
    private val rawMacPattern = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val disallowed = setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")

    /** Preserve original case: OEM SHA-256 lookup is case-sensitive. */
    fun hashHuObservedPhoneMac(raw: String): String? {
        if (raw != raw.trim() || !rawMacPattern.matches(raw)) return null
        if (raw.uppercase(Locale.ROOT) in disallowed) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    /**
     * Only an authenticated receiver can select an association.
     * Unknown receivers cannot inherit the Toyota OEM extension merely by connecting.
     * A persisted receiver-scoped HU-confirmed hash wins over legacy and local candidates.
     */
    fun chooseAutomaticHash(
        receiverVerified: Boolean,
        verifiedOemPrimary: Boolean,
        receiverHash: String?,
        legacyHash: String?,
        phoneClassicAddress: String?
    ): String? {
        if (!receiverVerified) return null
        if (receiverHash != null && hashPattern.matches(receiverHash)) return receiverHash
        if (!verifiedOemPrimary) return null
        if (legacyHash != null && hashPattern.matches(legacyHash)) return legacyHash
        return phoneClassicAddress?.let(::hashHuObservedPhoneMac)
    }

    /** OP_ADD=0 is proto3 default: omit field1, encode only tag(2,length)=12 40. */
    fun addHash(hash: String): ByteArray? =
        if (hashPattern.matches(hash)) ProtoWriter().string(2, hash).toByteArray() else null
}

/**
 * FT183: association is automatic when a verified OEM receiver has a trustworthy
 * phone Classic BT identity. No application-level opt-in or repeated prompts.
 * Android often masks the local adapter address: fail closed instead of using the
 * vehicle, BLE, P2P or a synthetic address. Raw MAC is never persisted.
 */
internal class IccoaBluetoothIdentityLinkStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        "iccoa-classic-bt-identity-link", Context.MODE_PRIVATE
    )

    fun configured(): Boolean = IccoaBluetoothMacInfo.addHash(
        prefs.getString("phone_bt_sha256", "") ?: ""
    ) != null

    fun enabled(): Boolean = prefs.getBoolean("enabled", false) && configured()

    fun saveHuObservedPhoneMac(raw: String): Boolean {
        val hash = IccoaBluetoothMacInfo.hashHuObservedPhoneMac(raw) ?: return false
        // New identity must be re-enabled explicitly, not sent on the next connection.
        return prefs.edit().putString("phone_bt_sha256", hash).putBoolean("enabled", false).commit()
    }

    fun setEnabled(value: Boolean): Boolean {
        if (value && !configured()) return false
        return prefs.edit().putBoolean("enabled", value).commit()
    }

    fun forget(): Boolean = prefs.edit().remove("phone_bt_sha256")
        .putBoolean("enabled", false).commit()

    /** Null means feature OFF/invalid; no unauthenticated packet may be sent. */
    fun addPayload(): ByteArray? = if (enabled())
        IccoaBluetoothMacInfo.addHash(prefs.getString("phone_bt_sha256", "") ?: "")
    else null

    private fun receiverPrefix(id: String?): String? =
        id?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { "verified_$it." }

    /** Each signed receiver has its own explicit consent and HU-observed phone MAC. */
    fun configured(receiverId: String): Boolean {
        val p = receiverPrefix(receiverId) ?: return false
        return IccoaBluetoothMacInfo.addHash(prefs.getString(p + "phone_bt_sha256", "") ?: "") != null
    }

    fun enabled(receiverId: String): Boolean {
        val p = receiverPrefix(receiverId) ?: return false
        return prefs.getBoolean(p + "enabled", false) && configured(receiverId)
    }

    fun saveHuObservedPhoneMac(receiverId: String, raw: String): Boolean {
        val p = receiverPrefix(receiverId) ?: return false
        val hash = IccoaBluetoothMacInfo.hashHuObservedPhoneMac(raw) ?: return false
        return prefs.edit().putString(p + "phone_bt_sha256", hash)
            .putBoolean(p + "enabled", false).commit()
    }

    fun setEnabled(receiverId: String, value: Boolean): Boolean {
        val p = receiverPrefix(receiverId) ?: return false
        if (value && !configured(receiverId)) return false
        return prefs.edit().putBoolean(p + "enabled", value).commit()
    }

    fun forget(receiverId: String): Boolean {
        val p = receiverPrefix(receiverId) ?: return false
        return prefs.edit().remove(p + "phone_bt_sha256")
            .putBoolean(p + "enabled", false).commit()
    }

    /**
     * Android's public BluetoothAdapter address is usually masked on modern
     * consumer phones. The exact case is preserved; do not normalize the
     * address before hashing, since Toyota compares the original text bytes.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun localPhoneClassicAddressOrNull(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 31 &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED) return null
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return null
        val raw = adapter.address ?: return null
        if (IccoaBluetoothMacInfo.hashHuObservedPhoneMac(raw) != null) raw else null
    }.getOrNull()

    /**
     * Only verified cryptographic receiver identity can index a stored hash.
     * For first-time discovery, only the independently proven Toyota PRIMARY
     * receiver may use the OEM extension without a saved receiver-scoped hash.
     * No OEM ACK/HFP guarantee follows from queuing this CONTROL message.
     */
    fun addPayload(receiverId: String?, verifiedLegacyPrimary: Boolean): ByteArray? {
        val p = receiverPrefix(receiverId) ?: return null
        val existing = prefs.getString(p + "phone_bt_sha256", null)
        val legacy = if (verifiedLegacyPrimary)
            prefs.getString("phone_bt_sha256", null) else null
        val discovered = if (existing == null && legacy == null && verifiedLegacyPrimary)
            localPhoneClassicAddressOrNull() else null
        val hash = IccoaBluetoothMacInfo.chooseAutomaticHash(
            receiverVerified = true,
            verifiedOemPrimary = verifiedLegacyPrimary,
            receiverHash = existing,
            legacyHash = legacy,
            phoneClassicAddress = discovered
        ) ?: return null
        if (existing != hash) {
            if (!prefs.edit().putString(p + "phone_bt_sha256", hash)
                    .putBoolean(p + "enabled", true)
                    .putBoolean("enabled", false).commit()) return null
        }
        return IccoaBluetoothMacInfo.addHash(hash)
    }
}
