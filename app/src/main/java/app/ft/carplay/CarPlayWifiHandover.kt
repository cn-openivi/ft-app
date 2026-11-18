package app.ft.carplay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

/**
 * Opt-in, single-session Android Wi-Fi handover using WifiNetworkSpecifier.
 * Invoked only after authenticated iAP2/real Toyota security profile, never
 * via CarConnection startup. Android may show system user consent: do not
 * fake Wi-Fi state, call connectWifiNetwork without authorization or skip UI.
 *
 * onAvailable means "Android Wi-Fi network acquired", NOT authenticated
 * AirPlay, streaming video, CarPlay ACTIVE or a confirmed HFP phone call.
 */
internal class CarPlayWifiHandover(private val context: Context) : AutoCloseable {
    enum class Stage {
        PERMISSION_MISSING, NETWORK_MANAGER_MISSING,
        AUTHENTICATION_REQUIRED, UNTRUSTED_START_SESSION,
        ALREADY_PENDING, USER_APPROVAL_PENDING,
        WIFI_NETWORK_AVAILABLE_UNVERIFIED, UNAVAILABLE, NETWORK_LOST,
        SECURITY_ERROR, CANCELLED
    }
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var generation: Long = 0L

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(
        candidate: CarPlayWifiStartExtractor.Candidate?,
        authenticatedIap2AndToyotaHu: Boolean,
        onStage: (Stage, Network?) -> Unit
    ): Stage {
        if (!authenticatedIap2AndToyotaHu) return Stage.AUTHENTICATION_REQUIRED
        if (candidate == null) return Stage.UNTRUSTED_START_SESSION
        if (callback != null) return Stage.ALREADY_PENDING
        if (manager == null) return Stage.NETWORK_MANAGER_MISSING
        val wifiPermission = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (context.checkSelfPermission(wifiPermission) != PackageManager.PERMISSION_GRANTED)
            return Stage.PERMISSION_MISSING
        val spec = try {
            WifiNetworkSpecifier.Builder()
                .setSsid(candidate.ssid)
                .setWpa2Passphrase(candidate.passphrase)
                .build()
        } catch (_: IllegalArgumentException) {
            return Stage.UNTRUSTED_START_SESSION
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val id = ++generation
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (id == generation) onStage(Stage.WIFI_NETWORK_AVAILABLE_UNVERIFIED, network)
            }
            override fun onUnavailable() {
                // An obsolete callback MUST NOT close a newer Wi-Fi session.
                if (id != generation) return
                try { onStage(Stage.UNAVAILABLE, null) }
                finally { if (id == generation) this@CarPlayWifiHandover.close() }
            }
            override fun onLost(network: Network) {
                if (id != generation) return
                try { onStage(Stage.NETWORK_LOST, null) }
                finally { if (id == generation) this@CarPlayWifiHandover.close() }
            }
        }
        callback = cb
        return try {
            manager.requestNetwork(request, cb, 15000)
            Stage.USER_APPROVAL_PENDING
        } catch (_: SecurityException) {
            callback = null
            generation++
            Stage.SECURITY_ERROR
        } catch (_: IllegalArgumentException) {
            callback = null
            generation++
            Stage.UNTRUSTED_START_SESSION
        }
    }

    @Synchronized override fun close() {
        generation++
        val cb = callback
        callback = null
        if (cb != null) runCatching { manager?.unregisterNetworkCallback(cb) }
    }
}
