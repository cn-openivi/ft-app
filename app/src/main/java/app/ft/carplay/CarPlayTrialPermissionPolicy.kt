package app.ft.carplay

/**
 * FT200 physical diagnostic needs one existing Android Bluetooth bond and
 * read-only HFP/SDP. It does not request Bluetooth scanning, advertising,
 * pairing or privileged access.
 */
internal object CarPlayTrialPermissionPolicy {
    const val CONNECT = "android.permission.BLUETOOTH_CONNECT"

    fun runtimePermission(sdkInt: Int): String? =
        if (sdkInt >= 31) CONNECT else null
}
