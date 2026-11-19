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
        UNAPPROVED_TOYOTA_WPA2_PROFILE,
        ALREADY_PENDING, USER_APPROVAL_PENDING,
        WIFI_NETWORK_AVAILABLE_UNVERIFIED, WIFI_NETWORK_REJECTED,
        UNAVAILABLE, NETWORK_LOST,
        SECURITY_ERROR, CANCELLED
    }
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var generation: Long = 0L
    private val networkGate = CarPlayWifiCallbackGate<Network>()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(
        candidate: CarPlayWifiStartExtractor.Candidate?,
        authenticatedIap2AndToyotaHu: Boolean,
        onStage: (Stage, Network?) -> Unit
    ): Stage {
        if (!authenticatedIap2AndToyotaHu) return Stage.AUTHENTICATION_REQUIRED
        if (candidate == null) return Stage.UNTRUSTED_START_SESSION
        return requestWifi(candidate.ssid, candidate.passphrase, onStage)
    }

    /**
     * Alternate physically advertised 0x5702/0x5703 dialect. The flat reply
     * does not carry a trustworthy WPA2 security enum; require a separately
     * verified FAW Toyota WPA2 profile before asking Android to connect.
     * Never assume all CarPlay devices share a security scheme.
     */
    @Synchronized
    fun startFlat(
        candidate: CarPlayFlatWifiInfoExtractor.Candidate?,
        authenticatedIap2AndToyotaHu: Boolean,
        independentlyApprovedToyotaWpa2Profile: Boolean,
        onStage: (Stage, Network?) -> Unit
    ): Stage {
        if (!authenticatedIap2AndToyotaHu) return Stage.AUTHENTICATION_REQUIRED
        if (!independentlyApprovedToyotaWpa2Profile)
            return Stage.UNAPPROVED_TOYOTA_WPA2_PROFILE
        if (candidate == null) return Stage.UNTRUSTED_START_SESSION
        return requestWifi(candidate.ssid, candidate.passphrase, onStage)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun requestWifi(
        verifiedSsid: String,
        verifiedWpa2Passphrase: String,
        onStage: (Stage, Network?) -> Unit
    ): Stage {
        if (callback != null) return Stage.ALREADY_PENDING
        if (manager == null) return Stage.NETWORK_MANAGER_MISSING
        val wifiPermission = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (context.checkSelfPermission(wifiPermission) != PackageManager.PERMISSION_GRANTED)
            return Stage.PERMISSION_MISSING
        val spec = try {
            WifiNetworkSpecifier.Builder()
                .setSsid(verifiedSsid)
                .setWpa2Passphrase(verifiedWpa2Passphrase)
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
        check(networkGate.begin(id))
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Android reports capabilities in a subsequent callback;
                // don't promote an address until Wi-Fi transport is observed.
                networkGate.available(id, network)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                when (networkGate.capabilities(id, network,
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))) {
                    CarPlayWifiCallbackGate.Verdict.NETWORK_WIFI_CANDIDATE -> {
                        if (id == generation)
                            onStage(Stage.WIFI_NETWORK_AVAILABLE_UNVERIFIED, network)
                    }
                    CarPlayWifiCallbackGate.Verdict.WRONG_TRANSPORT -> {
                        try { if (id == generation) onStage(Stage.WIFI_NETWORK_REJECTED, null) }
                        finally { if (id == generation) this@CarPlayWifiHandover.close() }
                    }
                    CarPlayWifiCallbackGate.Verdict.IGNORED -> Unit
                }
            }
            override fun onUnavailable() {
                // An obsolete callback MUST NOT close a newer Wi-Fi session.
                if (!networkGate.unavailable(id)) return
                try { if (id == generation) onStage(Stage.UNAVAILABLE, null) }
                finally { if (id == generation) this@CarPlayWifiHandover.close() }
            }
            override fun onLost(network: Network) {
                if (!networkGate.lost(id, network)) return
                try { if (id == generation) onStage(Stage.NETWORK_LOST, null) }
                finally { if (id == generation) this@CarPlayWifiHandover.close() }
            }
        }
        callback = cb
        return try {
            manager.requestNetwork(request, cb, 15000)
            Stage.USER_APPROVAL_PENDING
        } catch (_: SecurityException) {
            callback = null
            networkGate.close()
            generation++
            Stage.SECURITY_ERROR
        } catch (_: IllegalArgumentException) {
            callback = null
            networkGate.close()
            generation++
            Stage.UNTRUSTED_START_SESSION
        }
    }

    @Synchronized override fun close() {
        networkGate.close()
        generation++
        val cb = callback
        callback = null
        if (cb != null) runCatching { manager?.unregisterNetworkCallback(cb) }
    }
}
