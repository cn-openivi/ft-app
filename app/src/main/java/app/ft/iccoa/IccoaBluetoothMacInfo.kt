package app.ft.iccoa

import android.content.Context
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

    /** OP_ADD=0 is proto3 default: omit field1, encode only tag(2,length)=12 40. */
    fun addHash(hash: String): ByteArray? =
        if (hashPattern.matches(hash)) ProtoWriter().string(2, hash).toByteArray() else null
}

/**
 * Separate, disabled-by-default owner consent. Stores ONLY the validated hash
 * (not the original phone MAC) outside FT's ordinary exported Prefs snapshot.
 *
 * Save is not an OEM verification: user must obtain the EXACT Classic phone
 * address as rendered by Toyota HU. Never guess Android 17's local MAC.
 */
internal class IccoaBluetoothIdentityLinkStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
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
}
