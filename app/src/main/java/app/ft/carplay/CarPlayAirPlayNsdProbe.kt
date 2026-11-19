package app.ft.carplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * FT200.2: real network-scoped Android NSD browse and resolve, strictly
 * source-only until the Toyota iAP2/MFi and Wi-Fi handover are confirmed.
 *
 * Unlike legacy unbound mDNS, Android 13+ discovers on the exact Android
 * Network returned by an authorized Wi-Fi handover. No default-route fallback,
 * hardcoded AirPlay port, advertised fake service or RTSP connection.
 * DNS-SD endpoint observations are UNTRUSTED and never CarPlay ACTIVE.
 *
 * This class is never invoked by production CarConnection startup.
 */
internal class CarPlayAirPlayNsdProbe(private val context: Context) : AutoCloseable {
    enum class Status {
        IAP2_AUTH_REQUIRED, WIFI_NETWORK_MISSING, ANDROID_API_UNSUPPORTED,
        WIFI_NETWORK_WRONG, NSD_MANAGER_MISSING, ALREADY_RUNNING,
        DISCOVERY_REQUESTED, DISCOVERY_STARTED, DISCOVERY_FAILED,
        SERVICE_FOUND_UNVERIFIED, WRONG_NETWORK, RESOLUTION_FAILED,
        INVALID_RESOLVED_ENDPOINT, ENDPOINT_CANDIDATE_ONLY,
        SERVICE_LOST, TIMEOUT, CANCELLED
    }
    class Event(val status: Status, val endpoint: CarPlayAirPlayNsdPolicy.Result? = null) {
        override fun toString() = "CarPlayAirPlayNsdEvent(status=$status, endpoint redacted)"
    }

    private val manager = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var active = false
    @Volatile private var resolutionPending = false
    private var deadline: Runnable? = null
    @Volatile private var generation = 0L

    @Synchronized
    fun start(
        verifiedNetwork: Network?,
        iap2AndToyotaIdentityAuthenticated: Boolean,
        onEvent: (Event) -> Unit
    ): Status {
        if (!iap2AndToyotaIdentityAuthenticated) return Status.IAP2_AUTH_REQUIRED
        if (verifiedNetwork == null) return Status.WIFI_NETWORK_MISSING
        if (Build.VERSION.SDK_INT < 33) return Status.ANDROID_API_UNSUPPORTED
        if (discovery != null) return Status.ALREADY_RUNNING
        val capabilities = connectivity?.getNetworkCapabilities(verifiedNetwork)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true)
            return Status.WIFI_NETWORK_WRONG
        val nsd = manager ?: return Status.NSD_MANAGER_MISSING
        val run = ++generation

        fun emitIfCurrent(status: Status, result: CarPlayAirPlayNsdPolicy.Result? = null) {
            if (generation == run) onEvent(Event(status, result))
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (generation != run) {
                    // close() may have happened during asynchronous startup.
                    runCatching { nsd.stopServiceDiscovery(this) }
                    return
                }
                active = true
                emitIfCurrent(Status.DISCOVERY_STARTED)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                emitIfCurrent(Status.DISCOVERY_FAILED)
                if (generation == run) close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (generation != run || resolutionPending) return
                if (!CarPlayAirPlayNsdPolicy.isAirPlayVideoService(info.serviceType)) return
                if (info.network != verifiedNetwork) {
                    emitIfCurrent(Status.WRONG_NETWORK)
                    return
                }
                resolutionPending = true
                emitIfCurrent(Status.SERVICE_FOUND_UNVERIFIED)
                try {
                    nsd.resolveService(info, context.mainExecutor, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            if (generation != run) return
                            resolutionPending = false
                            emitIfCurrent(Status.RESOLUTION_FAILED)
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (generation != run) return
                            resolutionPending = false
                            val addresses = if (Build.VERSION.SDK_INT >= 34)
                                serviceInfo.hostAddresses
                            else listOfNotNull(serviceInfo.host)
                            val result = CarPlayAirPlayNsdPolicy.resolve(
                                serviceInfo.serviceType,
                                serviceInfo.network == verifiedNetwork,
                                serviceInfo.port,
                                addresses
                            )
                            emitIfCurrent(
                                if (result.state ==
                                    CarPlayAirPlayNsdPolicy.State.ENDPOINT_CANDIDATE_ONLY)
                                    Status.ENDPOINT_CANDIDATE_ONLY
                                else Status.INVALID_RESOLVED_ENDPOINT,
                                result
                            )
                        }
                    })
                } catch (_: SecurityException) {
                    resolutionPending = false
                    emitIfCurrent(Status.RESOLUTION_FAILED)
                } catch (_: RuntimeException) {
                    resolutionPending = false
                    emitIfCurrent(Status.RESOLUTION_FAILED)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (generation == run && info.network == verifiedNetwork)
                    emitIfCurrent(Status.SERVICE_LOST)
            }
        }
        discovery = listener
        deadline = Runnable {
            if (generation == run) {
                emitIfCurrent(Status.TIMEOUT)
                close()
            }
        }.also { main.postDelayed(it, 15_000L) }
        return try {
            nsd.discoverServices("_airplay._tcp.", NsdManager.PROTOCOL_DNS_SD,
                verifiedNetwork, context.mainExecutor, listener)
            Status.DISCOVERY_REQUESTED
        } catch (_: SecurityException) {
            close()
            Status.DISCOVERY_FAILED
        } catch (_: RuntimeException) {
            close()
            Status.DISCOVERY_FAILED
        }
    }

    @Synchronized override fun close() {
        generation++
        deadline?.let { main.removeCallbacks(it) }
        deadline = null
        val listener = discovery
        discovery = null
        resolutionPending = false
        if (active && listener != null) {
            active = false
            runCatching { manager?.stopServiceDiscovery(listener) }
        }
        // If closed before onDiscoveryStarted, the stale start callback stops
        // itself on entry; never leave a network-wide discovery running.
    }
}
