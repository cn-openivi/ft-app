package app.ft.aa

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import app.ft.core.Bytes
import app.ft.core.DiagLog
import app.ft.core.ProtoWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class HotspotInfo(val ssid: String, val password: String, val ip: String)

// The Wi-Fi credential response must never reach raw TX diagnostics,
// including opt-in packet tracing. This does not change the wire packet.
internal object AaBluetoothLogPolicy {
    fun mayLogPayload(type: Int): Boolean = type != AaProtocol.BT_WIFI_INFO_RESPONSE
}

@SuppressLint("MissingPermission")
class AaBluetoothAdvertiser(private val context: Context, private val port: Int) {
    private val tag = "AA-BT"
    private val running = AtomicBoolean(false)
    private var server: BluetoothServerSocket? = null
    private var job: Job? = null
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private val hotspotReady = CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())
    private val _info = MutableStateFlow<HotspotInfo?>(null)
    val info: StateFlow<HotspotInfo?> = _info
    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status

    fun start(scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return
        startHotspot()
        job = scope.launch(Dispatchers.IO) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                _status.value = "bluetooth off"
                DiagLog.w(tag, "bluetooth unavailable")
                return@launch
            }
            try {
                val ss = adapter.listenUsingRfcommWithServiceRecord("Android Auto Wireless", UUID.fromString(AaProtocol.BT_UUID))
                server = ss
                _status.value = "advertising"
                DiagLog.i(tag, "RFCOMM listening with AA wireless UUID")
                while (isActive && running.get()) {
                    val s = ss.accept()
                    DiagLog.i(tag, "phone ${s.remoteDevice.name} connected over BT")
                    handle(s)
                }
            } catch (t: Throwable) {
                if (running.get()) DiagLog.w(tag, "BT server ended: ${t.message}")
                _status.value = "stopped"
            }
        }
    }

    private fun startHotspot() {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspot = reservation
                    val (ssid, pass) = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val cfg = reservation.softApConfiguration
                        val name = if (android.os.Build.VERSION.SDK_INT >= 33) {
                            cfg.wifiSsid?.toString()?.trim('"')
                        } else {
                            @Suppress("DEPRECATION")
                            cfg.ssid
                        }
                        name.orEmpty() to cfg.passphrase.orEmpty()
                    } else {
                        @Suppress("DEPRECATION")
                        val cfg = reservation.wifiConfiguration
                        cfg?.SSID?.trim('"').orEmpty() to cfg?.preSharedKey.orEmpty()
                    }
                    if (!AaHotspotAddressPolicy.credentialsUsable(ssid, pass)) {
                        _status.value = "hotspot-wpa2-credentials-unavailable"
                        DiagLog.w(tag, "LOHS did not provide a valid WPA2 SSID/passphrase; no BT Wi-Fi advertisement")
                        _info.value = null
                        hotspotReady.countDown()
                        runCatching { reservation.close() }
                        return
                    }
                    // onStarted means SSID and credentials exist; the AP IPv4
                    // may be assigned later. Never advertise the station/VPN IP.
                    fun resolveAddress(attempt: Int) {
                        if (!running.get() || hotspot !== reservation) return
                        val candidates = app.ft.carlife.NetUtil.hotspotInterfaces(context)
                            .flatMap { iface -> iface.inetAddresses.toList() }
                            .mapNotNull { it.hostAddress }
                        val ip = AaHotspotAddressPolicy.select(candidates)
                        if (ip != null) {
                            _info.value = HotspotInfo(ssid, pass, ip)
                            _status.value = "hotspot-ready"
                            DiagLog.i(tag, "local-only hotspot ready ip=$ip")
                            hotspotReady.countDown()
                        } else if (attempt < 60) {
                            main.postDelayed({ resolveAddress(attempt + 1) }, 250L)
                        } else {
                            _status.value = "hotspot-ip-unavailable"
                            DiagLog.w(tag, "local-only hotspot IPv4 not independently verified")
                            hotspotReady.countDown()
                        }
                    }
                    resolveAddress(0)
                }

                override fun onFailed(reason: Int) {
                    _info.value = null
                    _status.value = "hotspot-failed-$reason"
                    DiagLog.w(tag, "hotspot failed reason=$reason")
                    hotspotReady.countDown()
                }
                override fun onStopped() {
                    _info.value = null
                    _status.value = "hotspot-stopped"
                    DiagLog.w(tag, "local-only hotspot stopped by Android")
                    hotspotReady.countDown()
                }
            }, main)
        } catch (t: Throwable) {
            _status.value = "hotspot-unavailable"
            hotspotReady.countDown()
            DiagLog.w(tag, "hotspot unavailable: ${t.javaClass.simpleName}")
        }
    }

    private fun handle(s: BluetoothSocket) {
        try {
            val i = s.inputStream
            val o = s.outputStream
            // RFCOMM may connect before Android has assigned the local-only
            // AP interface. Do not issue WIFI_START with a guessed gateway.
            if (!hotspotReady.await(16L, TimeUnit.SECONDS)) {
                DiagLog.w(tag, "AA BT connected before hotspot IP ready; retry later")
                return
            }
            val hs = _info.value ?: run {
                DiagLog.w(tag, "AA BT hotspot unavailable; refusing empty Wi-Fi credentials")
                return
            }
            write(o, AaProtocol.BT_WIFI_START_REQUEST,
                ProtoWriter().string(1, hs.ip).int32(2, port).toByteArray())
            while (running.get() && s.isConnected) {
                val h = ByteArray(4)
                Bytes.readFully(i, h)
                val len = Bytes.u16(h, 0)
                val type = Bytes.u16(h, 2)
                val body = ByteArray(len)
                Bytes.readFully(i, body)
                DiagLog.rx(tag, "bt type=$type", body)
                when (type) {
                    AaProtocol.BT_WIFI_INFO_REQUEST -> {
                        val info = _info.value ?: return
                        // If the hotspot address disappeared or changed, the
                        // ongoing BT handshake is stale; reconnect from scratch.
                        val currentIp = AaHotspotAddressPolicy.select(
                            app.ft.carlife.NetUtil.hotspotInterfaces(context)
                                .flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress })
                        if (currentIp != info.ip) {
                            _info.value = null
                            _status.value = "hotspot-ip-changed"
                            DiagLog.w(tag, "AA BT hotspot IPv4 changed; discard stale handshake")
                            return
                        }
                        write(
                            o, AaProtocol.BT_WIFI_INFO_RESPONSE,
                            ProtoWriter()
                                .string(1, info.ssid)
                                .string(2, info.password)
                                .string(3, "")
                                .enum(4, AaProtocol.WIFI_SECURITY_WPA2_PERSONAL)
                                .enum(5, AaProtocol.WIFI_AP_DYNAMIC)
                                .toByteArray()
                        )
                    }
                    AaProtocol.BT_WIFI_VERSION_REQUEST -> write(o, AaProtocol.BT_WIFI_VERSION_RESPONSE, ByteArray(0))
                    AaProtocol.BT_WIFI_CONNECT_STATUS -> DiagLog.i(tag, "phone wifi connect status received")
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            DiagLog.d(tag, "bt session ended: ${t.message}")
        } finally {
            runCatching { s.close() }
        }
    }

    private fun write(o: java.io.OutputStream, type: Int, body: ByteArray) {
        val f = ByteArray(4 + body.size)
        Bytes.putU16(body.size, f, 0)
        Bytes.putU16(type, f, 2)
        System.arraycopy(body, 0, f, 4, body.size)
        if (AaBluetoothLogPolicy.mayLogPayload(type)) {
            DiagLog.tx(tag, "bt type=$type", body)
        } else {
            DiagLog.d(tag, "TX bt type=$type bytes=${body.size} payload=REDACTED_WIFI_PASSWORD")
        }
        o.write(f)
        o.flush()
    }

    fun stop() {
        running.set(false)
        hotspotReady.countDown()
        runCatching { server?.close() }
        server = null
        runCatching { hotspot?.close() }
        hotspot = null
        _info.value = null
        _status.value = "idle"
        job?.cancel()
    }
}
