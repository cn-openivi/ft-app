package app.ft.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Core discovery permissions only. Audio, screen capture and notifications
 * have their own user-triggered permission or consent flows.
 * This gate is shared by manual and automatic connection.
 */
object ConnectionPermissions {
    fun requiredFor(sdk: Int): List<String> =
        if (sdk >= 33) listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) else if (sdk >= 31) listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missing(context: Context): Array<String> =
        requiredFor(Build.VERSION.SDK_INT)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
}
