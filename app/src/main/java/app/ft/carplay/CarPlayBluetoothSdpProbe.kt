package app.ft.carplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * FT200.2 bounded, read-only observation of ONE ALREADY-PAIRED Toyota device.
 *
 * Uses the system's real Bluetooth adapter. Does not create a new bond, spoof a MAC,
 * register a fabricated iAP2 SDP record, or negotiate Apple/MFi credentials.
 *
 * SDP UUIDs are capability hints only; they cannot prove Apple iAP2 compatibility.
 * Never infer CarPlay ACTIVE, actual HFP voice or a valid AirPlay session from this.
 */
internal class CarPlayBluetoothSdpProbe(private val context: Context) : AutoCloseable {
    enum class Result {
        PERMISSION_MISSING, NO_ADAPTER, ADAPTER_DISABLED, BAD_REMOTE_ADDRESS,
        NOT_BONDED, CACHED_UUIDS, SDP_REQUESTED, SDP_RECEIVED,
        SDP_START_REJECTED, SDP_TIMEOUT, OBSERVATION_FAILED, CLOSED
    }

    data class Observation(
        val result: Result,
        val remoteAddress: String?,
        val remoteUuidStrings: List<String> = emptyList(),
        val completedFreshSdp: Boolean = false
    )

    private var receiver: BroadcastReceiver? = null
    private var callback: ((Observation) -> Unit)? = null
    private var address: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val epoch = CarPlaySdpObservationEpoch()
    private var deadline: Runnable? = null

    /**
     * Explicit owner/diagnostic invocation only. Request SDP for an existing bond.
     * One outstanding probe at a time; caller must close on cancellation.
     */
    @SuppressLint("MissingPermission")
    fun start(remoteToyotaAddress: String, onObservation: (Observation) -> Unit) {
        close()
        callback = onObservation
        val requiredPermission =
            if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT
            else Manifest.permission.BLUETOOTH
        if (context.checkSelfPermission(requiredPermission) != PackageManager.PERMISSION_GRANTED) {
            emit(Result.PERMISSION_MISSING, null)
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            emit(Result.NO_ADAPTER, null)
            return
        }
        if (!adapter.isEnabled) {
            emit(Result.ADAPTER_DISABLED, null)
            return
        }
        if (!BluetoothAdapter.checkBluetoothAddress(remoteToyotaAddress)) {
            emit(Result.BAD_REMOTE_ADDRESS, null)
            return
        }
        val device = try {
            adapter.bondedDevices.firstOrNull {
                it.address.equals(remoteToyotaAddress, ignoreCase = true)
            }
        } catch (_: SecurityException) {
            emit(Result.PERMISSION_MISSING, null)
            return
        } catch (_: RuntimeException) {
            emit(Result.OBSERVATION_FAILED, null)
            return
        }
        if (device == null || device.bondState != BluetoothDevice.BOND_BONDED) {
            emit(Result.NOT_BONDED, null)
            return
        }
        address = device.address
        emit(Result.CACHED_UUIDS, device.uuids?.map { it.uuid.toString() }?.sorted().orEmpty())
        val run = epoch.begin()

        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        val watcher = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (receiver !== this || !epoch.accepts(run) ||
                    intent.action != BluetoothDevice.ACTION_UUID) return
                val observed: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                if (observed?.address?.equals(device.address, ignoreCase = true) != true)
                    return
                // Re-read system's current remote UUID cache after the matching broadcast.
                @SuppressLint("MissingPermission")
                val uuidStrings = try {
                    observed.uuids?.map { it.uuid.toString() }?.sorted().orEmpty()
                } catch (_: SecurityException) {
                    if (epoch.complete(run)) emit(Result.PERMISSION_MISSING, null)
                    if (receiver === this) close()
                    return
                }
                if (epoch.complete(run)) emit(Result.SDP_RECEIVED, uuidStrings, fresh = true)
                // UI callbacks may synchronously start a NEW SDP observation.
                // Its receiver must never be closed by this OLD callback.
                if (receiver === this) close()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Bluetooth system broadcasts may originate from its Bluetooth stack process.
                // The addressed, already-bonded device is checked above; SDP remains untrusted
                // until separately validated by real iAP2 authentication and HU evidence.
                context.registerReceiver(watcher, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(watcher, filter)
            }
            receiver = watcher
            val started = device.fetchUuidsWithSdp()
            if (started) {
                emit(Result.SDP_REQUESTED, emptyList())
                if (receiver === watcher && epoch.accepts(run)) {
                    deadline = Runnable {
                        if (receiver === watcher && epoch.complete(run)) {
                            emit(Result.SDP_TIMEOUT, emptyList())
                            if (receiver === watcher) close()
                        }
                    }.also { main.postDelayed(it, 15_000L) }
                }
            } else {
                emit(Result.SDP_START_REJECTED, emptyList())
                if (receiver === watcher) close()
            }
        } catch (_: SecurityException) {
            emit(Result.PERMISSION_MISSING, emptyList())
            close()
        } catch (_: RuntimeException) {
            emit(Result.OBSERVATION_FAILED, emptyList())
            close()
        }
    }

    private fun emit(result: Result, uuids: List<String>?, fresh: Boolean = false) {
        callback?.invoke(Observation(result, address, uuids.orEmpty(), fresh))
    }

    override fun close() {
        epoch.close()
        deadline?.let { main.removeCallbacks(it) }
        deadline = null
        val watcher = receiver
        receiver = null
        if (watcher != null) {
            try { context.unregisterReceiver(watcher) } catch (_: IllegalArgumentException) { }
        }
        address = null
        callback = null
    }
}
