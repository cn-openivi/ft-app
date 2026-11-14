package app.ft.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class BluetoothCallsStatus { CHECKING, CONNECTED, DISCONNECTED, OFF, PERMISSION_REQUIRED, UNAVAILABLE }

data class BluetoothProfilePeer(val address: String, val name: String)

data class BluetoothCallsState(
    val status: BluetoothCallsStatus = BluetoothCallsStatus.CHECKING,
    val devices: List<String> = emptyList(),
    /** Only actual connected HFP devices. Never infer these from paired/nearby devices. */
    val peers: List<BluetoothProfilePeer> = emptyList()
)

data class BluetoothMediaState(
    val status: BluetoothCallsStatus = BluetoothCallsStatus.CHECKING,
    val peers: List<BluetoothProfilePeer> = emptyList()
)

/** Observes the real HFP profile; BLE discovery and an ACL connection are not call audio. */
@SuppressLint("MissingPermission")
class BluetoothCalls(
    context: Context,
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val hasPermission: () -> Boolean = {
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
) : AutoCloseable {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(BluetoothCallsState())
    val state: StateFlow<BluetoothCallsState> = mutableState
    private val mutableMediaState = MutableStateFlow(BluetoothMediaState())
    /** A2DP is observed independently: HFP connected does NOT mean media connected. */
    val mediaState: StateFlow<BluetoothMediaState> = mutableMediaState
    private var profile: BluetoothProfile? = null
    private var mediaProfile: BluetoothProfile? = null
    private var mediaListener: BluetoothProfile.ServiceListener? = null
    private var mediaServiceAvailable = false
    private var mediaRequestRejected = false
    private var serviceAvailable = false
    private var listener: BluetoothProfile.ServiceListener? = null
    private var registered = false
    private var closed = false
    private val timeout = Runnable {
        if (!closed && mutableState.value.status == BluetoothCallsStatus.CHECKING) {
            publish(BluetoothCallsState(BluetoothCallsStatus.UNAVAILABLE))
        }
    }
    private val mediaTimeout = Runnable {
        if (!closed && mutableMediaState.value.status == BluetoothCallsStatus.CHECKING) {
            publishMedia(BluetoothMediaState(BluetoothCallsStatus.UNAVAILABLE))
        }
    }
    private fun requestProfile() {
        val callback = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                if (closed || listener !== this) {
                    runCatching { adapter?.closeProfileProxy(id, proxy) }
                    return
                }
                handler.removeCallbacks(timeout)
                profile?.takeIf { it !== proxy }?.let {
                    runCatching { adapter?.closeProfileProxy(id, it) }
                }
                profile = proxy
                serviceAvailable = true
                refresh()
            }

            override fun onServiceDisconnected(id: Int) {
                if (!closed && listener === this) {
                    // Keep the current binding for Android's transient service reconnect.
                    serviceAvailable = false
                    refresh()
                }
            }
        }
        listener = callback
        beginWait()
        if (adapter?.getProfileProxy(context, callback, BluetoothProfile.HEADSET) != true) {
            releaseProfile()
            publish(BluetoothCallsState(BluetoothCallsStatus.UNAVAILABLE))
        }
    }

    private fun beginWait() {
        publish(BluetoothCallsState())
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 5_000)
    }

    private fun releaseProfile() {
        handler.removeCallbacks(timeout)
        // Invalidate pending callbacks before closing the old binding.
        listener = null
        serviceAvailable = false
        val previous = profile
        profile = null
        previous?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    fun start() {
        if (closed) return
        if (!registered) {
            runCatching {
                // Bluetooth broadcasts originate from a privileged process outside the system UID.
                ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                }, ContextCompat.RECEIVER_EXPORTED)
                registered = true
            }.onFailure { DiagLog.w("BluetoothCalls", "HFP broadcasts unavailable: ${it.javaClass.simpleName}") }
        }
        refresh()
    }

    fun refresh() {
        if (closed) return
        try {
            when {
                !hasPermission() -> {
                    releaseProfile()
                    publish(BluetoothCallsState(BluetoothCallsStatus.PERMISSION_REQUIRED))
                }
                adapter == null -> publish(BluetoothCallsState(BluetoothCallsStatus.UNAVAILABLE))
                !adapter.isEnabled -> {
                    releaseProfile()
                    publish(BluetoothCallsState(BluetoothCallsStatus.OFF))
                }
                profile != null && serviceAvailable -> {
                    val devices = profile!!.connectedDevices
                    publish(BluetoothCallsState(
                        if (devices.isEmpty()) BluetoothCallsStatus.DISCONNECTED else BluetoothCallsStatus.CONNECTED,
                        devices.map { it.name?.takeIf(String::isNotBlank) ?: it.address }.sorted(),
                        connectedPeers(devices)
                    ))
                }
                listener == null -> requestProfile()
                mutableState.value.status != BluetoothCallsStatus.CHECKING &&
                    mutableState.value.status != BluetoothCallsStatus.UNAVAILABLE -> beginWait()
            }
        } catch (_: SecurityException) {
            releaseProfile()
            publish(BluetoothCallsState(BluetoothCallsStatus.PERMISSION_REQUIRED))
        } catch (e: Exception) {
            publish(BluetoothCallsState(BluetoothCallsStatus.UNAVAILABLE))
            DiagLog.w("BluetoothCalls", "HFP query failed: ${e.javaClass.simpleName}")
        } finally {
            refreshMedia()
        }
    }

    private fun connectedPeers(devices: List<BluetoothDevice>): List<BluetoothProfilePeer> =
        devices.mapNotNull { device ->
            val address = runCatching { device.address }.getOrNull()?.uppercase(Locale.ROOT)
                ?: return@mapNotNull null
            if (!CLASSIC_ADDRESS.matches(address) || address in INVALID_ADDRESSES) return@mapNotNull null
            val name = runCatching { device.name?.takeIf(String::isNotBlank) }.getOrNull() ?: address
            BluetoothProfilePeer(address, name)
        }.distinctBy { it.address }.sortedBy { it.address }

    private fun beginMediaWait() {
        publishMedia(BluetoothMediaState())
        handler.removeCallbacks(mediaTimeout)
        // Separate from the HFP timeout and its existing acceptance tests.
        handler.postDelayed(mediaTimeout, 6_000)
    }

    private fun requestMediaProfile() {
        val callback = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                if (closed || mediaListener !== this) {
                    runCatching { adapter?.closeProfileProxy(id, proxy) }
                    return
                }
                handler.removeCallbacks(mediaTimeout)
                mediaProfile?.takeIf { it !== proxy }?.let {
                    runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
                }
                mediaProfile = proxy
                mediaServiceAvailable = true
                refreshMedia()
            }

            override fun onServiceDisconnected(id: Int) {
                if (!closed && mediaListener === this) {
                    mediaServiceAvailable = false
                    beginMediaWait()
                }
            }
        }
        mediaListener = callback
        beginMediaWait()
        if (adapter?.getProfileProxy(context, callback, BluetoothProfile.A2DP) != true) {
            mediaRequestRejected = true
            mediaListener = null
            handler.removeCallbacks(mediaTimeout)
            publishMedia(BluetoothMediaState(BluetoothCallsStatus.UNAVAILABLE))
        }
    }

    private fun releaseMediaProfile() {
        handler.removeCallbacks(mediaTimeout)
        mediaListener = null
        mediaServiceAvailable = false
        mediaRequestRejected = false
        val previous = mediaProfile
        mediaProfile = null
        previous?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
    }

    private fun refreshMedia() {
        if (closed) return
        try {
            when {
                !hasPermission() -> {
                    releaseMediaProfile()
                    publishMedia(BluetoothMediaState(BluetoothCallsStatus.PERMISSION_REQUIRED))
                }
                adapter == null -> publishMedia(BluetoothMediaState(BluetoothCallsStatus.UNAVAILABLE))
                !adapter.isEnabled -> {
                    releaseMediaProfile()
                    publishMedia(BluetoothMediaState(BluetoothCallsStatus.OFF))
                }
                mediaProfile != null && mediaServiceAvailable -> {
                    val devices = mediaProfile!!.connectedDevices
                    publishMedia(BluetoothMediaState(
                        if (devices.isEmpty()) BluetoothCallsStatus.DISCONNECTED else BluetoothCallsStatus.CONNECTED,
                        connectedPeers(devices)
                    ))
                }
                mediaRequestRejected -> publishMedia(BluetoothMediaState(BluetoothCallsStatus.UNAVAILABLE))
                mediaListener == null -> requestMediaProfile()
                mutableMediaState.value.status != BluetoothCallsStatus.CHECKING &&
                    mutableMediaState.value.status != BluetoothCallsStatus.UNAVAILABLE -> beginMediaWait()
            }
        } catch (_: SecurityException) {
            releaseMediaProfile()
            publishMedia(BluetoothMediaState(BluetoothCallsStatus.PERMISSION_REQUIRED))
        } catch (e: Exception) {
            publishMedia(BluetoothMediaState(BluetoothCallsStatus.UNAVAILABLE))
            DiagLog.w("BluetoothCalls", "A2DP query failed: " + e.javaClass.simpleName)
        }
    }

    private fun publishMedia(value: BluetoothMediaState) {
        if (mutableMediaState.value != value) {
            mutableMediaState.value = value
            DiagLog.i("BluetoothCalls", "A2DP " + value.status + "; peer_count=" + value.peers.size)
        }
    }

    private fun publish(value: BluetoothCallsState) {
        if (mutableState.value != value) {
            mutableState.value = value
            DiagLog.i("BluetoothCalls", "HFP ${value.status}; devices=${value.devices.joinToString().ifBlank { "none" }}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        releaseProfile()
        releaseMediaProfile()
    }

    private companion object {
        val CLASSIC_ADDRESS = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
        val INVALID_ADDRESSES = setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")
    }
}
