package app.ft.supercast.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Discover only ADB services belonging to THIS phone.
 * NSD service ports are ephemeral and not user settings or authentication.
 */
internal class WirelessAdbDiscovery(
    context: Context,
    private val serviceType: String
) : NsdManager.DiscoveryListener {
    companion object {
        const val PAIRING = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"

        suspend fun findConnect(context: Context, timeoutMs: Long = 5_000): Int? {
            val scanner = WirelessAdbDiscovery(context.applicationContext, CONNECT)
            return try {
                scanner.start()
                withTimeoutOrNull(timeoutMs) { scanner.endpoint.filterNotNull().first().port }
            } finally {
                scanner.stop()
            }
        }
    }

    data class Endpoint(val serviceName: String, val port: Int)

    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _endpoint = MutableStateFlow<Endpoint?>(null)
    val endpoint: StateFlow<Endpoint?> = _endpoint
    private var started = false
    private var registered = false

    fun start() {
        if (started) return
        started = true
        try { manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, this) }
        catch (_: RuntimeException) { started = false; _endpoint.value = null }
    }

    fun stop() {
        started = false
        _endpoint.value = null
        if (registered) {
            registered = false
            runCatching { manager.stopServiceDiscovery(this) }
        }
    }

    override fun onDiscoveryStarted(type: String) { registered = true }
    override fun onStartDiscoveryFailed(type: String, errorCode: Int) { stop() }
    override fun onStopDiscoveryFailed(type: String, errorCode: Int) { registered = false }
    override fun onDiscoveryStopped(type: String) { registered = false }

    override fun onServiceFound(info: NsdServiceInfo) {
        if (!started) return
        // resolveService supports pre-API34 implementations without a custom executor.
            manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (!started || serviceInfo.port !in 1..65535) return
                if (!isOurOwnInterface(serviceInfo.host)) return
                _endpoint.value = Endpoint(serviceInfo.serviceName, serviceInfo.port)
            }
        })
    }

    override fun onServiceLost(info: NsdServiceInfo) {
        if (_endpoint.value?.serviceName == info.serviceName) _endpoint.value = null
    }

    private fun isOurOwnInterface(host: InetAddress?): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress) return true
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            Collections.list(interfaces).any { iface ->
                Collections.list(iface.inetAddresses).any { addr ->
                    addr == host
                }
            }
        } catch (_: Exception) { false }
    }
}
