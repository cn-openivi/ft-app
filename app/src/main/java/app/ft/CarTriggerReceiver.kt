package app.ft

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.ft.core.CarConnection
import app.ft.core.DiagLog
import app.ft.core.Prefs

class CarTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.autoConnect) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                DiagLog.i("Trigger", "starting after ${intent.action}")
                CarConnection.startAuto(context)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val name = try {
                    dev?.name.orEmpty()
                } catch (e: SecurityException) {
                    DiagLog.w("Trigger", "bluetooth permission was denied; ignoring connection trigger")
                    return
                }
                val want = prefs.carBtName.trim()
                val knownAddress = prefs.carBtAddress.trim()
                val matched = try {
                    (knownAddress.isNotBlank() && dev?.address.equals(knownAddress, ignoreCase = true)) ||
                        (want.isNotEmpty() && name.contains(want, true))
                } catch (_: SecurityException) { false }
                // Unknown Bluetooth devices must not wake an unrelated car session.
                if (matched) {
                    DiagLog.i("Trigger", "known car bluetooth connected; starting")
                    CarConnection.startAuto(context)
                }
            }
        }
    }
}
