package app.ft.carplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Read-only FT200.3 HFP profile evidence via documented Android public APIs.
 * No profile connect/disconnect, bond, MAC spoofing, AT command or voice capture.
 * A connected HFP profile does NOT prove that actual far-end carrier voice works.
 */
internal object CarPlayHfpProfileObserver {
    enum class Error {
        NONE, MISSING_PERMISSION, ADAPTER_UNAVAILABLE, ADAPTER_OFF,
        HEADSET_PROXY_UNAVAILABLE, PROFILE_DISCONNECTED, PROFILE_ACCESS_DENIED
    }
    data class Snapshot(
        val error: Error,
        val bondPolicy: CarPlaySingleBondPolicy.Result
    )

    @SuppressLint("MissingPermission")
    fun readOnce(
        context: Context,
        selectedToyotaAddress: String,
        onComplete: (Snapshot) -> Unit
    ): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 31)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            onComplete(Snapshot(Error.MISSING_PERMISSION,
                CarPlaySingleBondPolicy.Result.BT_STATE_UNAVAILABLE))
            return false
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            onComplete(Snapshot(Error.ADAPTER_UNAVAILABLE,
                CarPlaySingleBondPolicy.Result.BT_STATE_UNAVAILABLE))
            return false
        }
        val bonded = try {
            if (!adapter.isEnabled) {
                onComplete(Snapshot(Error.ADAPTER_OFF,
                    CarPlaySingleBondPolicy.Result.BT_STATE_UNAVAILABLE))
                return false
            }
            adapter.bondedDevices.map { it.address }.toSet()
        } catch (_: SecurityException) {
            onComplete(Snapshot(Error.MISSING_PERMISSION,
                CarPlaySingleBondPolicy.Result.BT_STATE_UNAVAILABLE))
            return false
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HEADSET) return
                val result = try {
                    val connected = proxy.connectedDevices
                    val selected = connected.firstOrNull {
                        it.address.equals(selectedToyotaAddress, ignoreCase = true)
                    } ?: connected.firstOrNull()
                    Snapshot(
                        Error.NONE,
                        CarPlaySingleBondPolicy.evaluate(
                            CarPlaySingleBondPolicy.Snapshot(
                                selectedToyotaAddress,
                                bonded,
                                selected?.address,
                                connected.isNotEmpty()
                            )
                        )
                    )
                } catch (_: SecurityException) {
                    Snapshot(Error.PROFILE_ACCESS_DENIED,
                        CarPlaySingleBondPolicy.Result.BT_STATE_UNAVAILABLE)
                } finally {
                    runCatching { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
                }
                onComplete(result)
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET)
                    onComplete(Snapshot(Error.PROFILE_DISCONNECTED,
                        CarPlaySingleBondPolicy.Result.BT_STATE_UNAVAILABLE))
            }
        }
        val requested = try {
            adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
        } catch (_: SecurityException) { false }
        if (!requested) onComplete(Snapshot(Error.HEADSET_PROXY_UNAVAILABLE,
            CarPlaySingleBondPolicy.Result.BT_STATE_UNAVAILABLE))
        return requested
    }
}
