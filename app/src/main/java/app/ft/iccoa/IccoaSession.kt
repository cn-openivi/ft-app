package app.ft.iccoa

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.ft.core.DiagLog
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.security.MessageDigest
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards asynchronous Android Network callbacks against a replaced Wi-Fi handle.
 * Network equality, not callback arrival order, defines the current transport.
 */
/** PIN lifecycle for one ICCOA attempt, never a credential cache. */
internal object IccoaNetworkPinLifecycle {
    /** Network replacement is not an authentication rejection. */
    fun afterAuthQueued(pin: String?): String? = pin
    /** A verified handshake no longer needs the submitted PIN. */
    fun afterVerifiedAuth(): String? = null
}

internal class IccoaNetworkCallbackGate<T : Any> {
    private var selected: T? = null
    @Synchronized fun onAvailable(network: T) { selected = network }
    @Synchronized fun isCurrent(network: T): Boolean = selected == network
    /** Commit authentication state only while the originating Network still owns the lane. */
    @Synchronized fun commitIfCurrent(network: T, commit: () -> Unit): Boolean {
        if (selected != network) return false
        commit()
        return true
    }
    /** Atomically reject queued work for a superseded Network before socket binding. */
    @Synchronized fun bindIfCurrent(network: T, bind: () -> Unit): Boolean {
        if (selected != network) return false
        bind()
        return true
    }
    @Synchronized fun onLost(network: T): Boolean {
        if (!isCurrent(network)) return false
        selected = null
        return true
    }
    @Synchronized fun clear() { selected = null }
}

/** Typed ICCOA terminal action; Quick reject and transport loss must never collide. */
enum class IccoaCloseAction { STOP, RETRY_TRANSPORT, REQUIRE_NORMAL_PIN }

/** A delayed gateway lookup belongs to one Network, never to the whole Attempt. */
internal class IccoaGatewayRetryGate<T> {
    private var pending: T? = null
    @Synchronized fun schedule(network: T): Boolean {
        if (pending == network) return false
        pending = network
        return true
    }
    @Synchronized fun consume(network: T): Boolean {
        if (pending != network) return false
        pending = null
        return true
    }
    @Synchronized fun clear() { pending = null }
}

data class IccoaCloseEvent(val message: String, val action: IccoaCloseAction) {
    companion object {
        fun classify(message: String, transportFailure: Boolean = false,
                     quickNormalFallback: Boolean = false): IccoaCloseEvent {
            require(!(transportFailure && quickNormalFallback)) {
                "Quick PIN fallback cannot also be a transport retry"
            }
            val action = when {
                quickNormalFallback -> IccoaCloseAction.REQUIRE_NORMAL_PIN
                transportFailure -> IccoaCloseAction.RETRY_TRANSPORT
                else -> IccoaCloseAction.STOP
            }
            return IccoaCloseEvent(message, action)
        }

        /** OEM CONTROL 1/23 is consumed in the session, not in the service. */
        fun isRemoteDisconnect(category: Int, method: Int) =
            category == 1 && method == 23
    }
}

/**
 * One independently owned ICCOA connection: GATT bootstrap, AP join, PIN SignIn and UCAR lanes.
 * Current AP credentials and agreement keys are never persisted. If owner chooses
 * automatic remembered-car reconnect, only the previously confirmed PIN is kept
 * encrypted under a non-exportable AndroidKeyStore key; never logged.
 * Status/PIN/closed callbacks run on main; ready and messages run on network workers.
 * [onReady] must synchronously open media listeners before returning (before AuthConfirm).
 */
@SuppressLint("MissingPermission")
class IccoaSession(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onPinRequired: () -> Unit,
    private val onReady: (Network, String) -> Unit,
    private val onMessage: (Int, Int, ByteArray) -> Unit,
    private val onClosed: (IccoaCloseEvent) -> Unit,
    private val onControlReady: () -> Unit = {},
    private val trustedCarName: String = "",
    private val tryRememberedPin: Boolean = false,
    private val forceCurrentPin: Boolean = false
) {
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val trustedPin by lazy { IccoaTrustedPin(context) }
    private val trustedCarKey by lazy { IccoaTrustedCarKey(context) }
    @Volatile private var current: Attempt? = null
    val cipher: IccoaControl.Cipher? get() = current?.cipher

    private class Attempt(val profile: IccoaClientProfile) {
        val clientId get() = profile.id
        val resources = mutableListOf<Closeable>()
        val workers = Executors.newCachedThreadPool { Thread(it, "ICCOA-session") }
        val controlSender = sender("ICCOA-control-send", 32)
        val audioSender = sender("ICCOA-audio-send", 8)
        val sequence = AtomicInteger(1000)
        val serverInfo = ByteArrayOutputStream()
        @Volatile var closed = false
        @Volatile var cipher: IccoaControl.Cipher? = null
        @Volatile var control: Socket? = null
        @Volatile var audio: Socket? = null
        @Volatile var controlWritingAt = 0L
        @Volatile var audioWritingAt = 0L
        var firstHeartbeatWritten = false // Accessed only by the control sender.
        var gatt: BluetoothGatt? = null
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        var network: Network? = null
        val networkGate = IccoaNetworkCallbackGate<Network>()
        var servicesRequested = false
        var servicesHandled = false
        var pinReady = false
        var pin: String? = null
        var pinAttempt = 0
        var reusingPin = false
        var quickMode = false
        var pinnedCarIdentity: ByteArray? = null
        val trustedCommitGate = IccoaTrustedCommitGate()
        var pendingTrustedCarIdentity: ByteArray? = null
        var wifiRequested = false
        var authStarted = false
        var hasWifiAddress = false
        val gatewayRetry = IccoaGatewayRetryGate<Network>()
        var gatewayWaitStatusNetwork: Network? = null

        companion object {
            private fun sender(name: String, capacity: Int) = ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity),
                { task -> Thread(task, name) }, ThreadPoolExecutor.AbortPolicy()
            )
        }
    }

    @Synchronized fun start(address: String) {
        stop()
        val attempt = Attempt(IccoaClientProfile.load(context))
        current = attempt
        post(attempt) {
            try {
                status(attempt, "Подключение ICCOA по Bluetooth…")
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                require(adapter?.isEnabled == true) { "Bluetooth disabled" }
                synchronized(attempt) {
                    if (!active(attempt)) return@post
                    attempt.gatt = adapter.getRemoteDevice(address).connectGatt(
                        context, false, bluetoothCallback(attempt), BluetoothDevice.TRANSPORT_LE
                    ) ?: error("GATT unavailable")
                }
                later(attempt, 20_000) {
                    // pinReady becomes false again while a submitted PIN is in flight.
                    // The initial Bluetooth watchdog must stop once bootstrap is ready.
                    if (!attempt.servicesHandled && !attempt.wifiRequested) fail(attempt, "Тайм-аут подключения ICCOA по Bluetooth", transportFailure = true)
                }
            } catch (e: Exception) { fail(attempt, "Не удалось подключиться по Bluetooth (${e.javaClass.simpleName})",
                transportFailure = e !is SecurityException && e.message != "Bluetooth disabled") }
        }
    }

    /** OEM Quick does not send a previous PIN over GATT. */
    private fun submitQuick(attempt: Attempt, pinned: ByteArray) = post(attempt) {
        if (!attempt.pinReady || attempt.wifiRequested || pinned.size != 64) return@post
        try {
            val gatt = attempt.gatt ?: error("Bluetooth unavailable")
            val characteristic = gatt.getService(SERVICE)?.getCharacteristic(CLIENT_INFO)
                ?: error("ICCOA characteristic unavailable")
            val payload = attempt.profile.clientInfoQuick(Build.MODEL, IccoaClientProfile.channel(context))
            require(payload.size <= 512)
            attempt.pinReady = false
            attempt.quickMode = true
            attempt.pinnedCarIdentity = pinned.copyOf()
            val revision = ++attempt.pinAttempt
            attempt.serverInfo.reset()
            status(attempt, "ICCOA Quick: проверка доверенного телефона…")
            val queued = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            payload.fill(0)
            if (!queued) {
                fail(attempt, "QUICK: ошибка очереди Bluetooth", transportFailure = true)
                return@post
            }
            later(attempt, 12_000) {
                if (!attempt.wifiRequested && revision == attempt.pinAttempt) {
                    // An absent BLE response is ambiguous, not a revocation of trust.
                    requestPin(attempt, "Quick не подтверждён. Введите текущий PIN автомобиля")
                }
            }
        } catch (e: Exception) {
            fail(attempt, "QUICK: ошибка Bluetooth (" + e.javaClass.simpleName + ")",
                transportFailure = e is java.io.IOException)
        }
    }

    fun submitPin(pin: String) = submitPin(pin, fromCache = false)

    private fun submitPin(pin: String, fromCache: Boolean) {
        val attempt = current ?: return
        post(attempt) {
            if (!pin.matches(Regex("[0-9]{6}"))) {
                status(attempt, "Введите шестизначный код с экрана автомобиля")
                onPinRequired()
                return@post
            }
            if (!attempt.pinReady || attempt.wifiRequested) return@post
            try {
                val gatt = attempt.gatt ?: error("GATT unavailable")
                val characteristic = gatt.getService(SERVICE)?.getCharacteristic(CLIENT_INFO)
                    ?: error("Missing client characteristic")
                val payload = attempt.profile.clientInfo(pin, Build.MODEL, IccoaClientProfile.channel(context))
                require(payload.size <= 512) { "Client info too large" }
                attempt.pinReady = false
                attempt.pin = pin
                attempt.reusingPin = fromCache
                attempt.quickMode = false
                attempt.pinnedCarIdentity = null
                val revision = ++attempt.pinAttempt
                attempt.serverInfo.reset()
                status(attempt, "Проверка кода ICCOA…")
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = payload
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                payload.fill(0)
                if (!accepted) {
                    // An Android GATT queue failure cannot reject a PIN.
                    // Re-scan the remembered car only for automatic attempts;
                    // explicit manual pairing still asks the owner for a code.
                    if (fromCache) fail(attempt,
                        "Не удалось поставить сохранённый код в очередь Bluetooth",
                        transportFailure = true)
                    else requestPin(attempt, "Не удалось отправить код. Введите текущий PIN")
                    return@post
                }
                later(attempt, 12_000) {
                    if (!attempt.wifiRequested && revision == attempt.pinAttempt) {
                        // No BLE response is ambiguous: the car may have ignored a
                        // rejected cached PIN, not just lost a transport packet.
                        // Never automatically replay a possibly invalid PIN.
                        // Keep the encrypted copy until explicit protocol proof
                        // of rejection, but require owner input for this attempt.
                        if (attempt.reusingPin)
                            DiagLog.w("ICCOA", "Cached PIN: no BLE reply; manual verification required")
                        requestPin(attempt, "Автомобиль не ответил вовремя. Введите текущий PIN")
                    }
                }
            } catch (e: Exception) {
                fail(attempt, "Ошибка отправки кода ICCOA (${e.javaClass.simpleName})",
                    transportFailure = fromCache &&
                        (e is java.io.IOException || e is IllegalStateException))
            }
        }
    }

    /** Explicit stop is silent; terminal transport failures invoke onClosed exactly once. */
    @Synchronized fun stop() {
        val attempt = current ?: return
        current = null
        close(attempt)
    }

    fun decryptInput(body: ByteArray): ByteArray =
        (cipher ?: error("ICCOA authentication is not complete")).decryptUibc(body)

    fun sendControl(method: Int, body: ByteArray): Boolean = current?.let { send(it, 1, method, body, 1) } ?: false
    fun sendAudio(method: Int, body: ByteArray): Boolean = current?.let { send(it, 4, method, body, 0) } ?: false

    private fun send(attempt: Attempt, category: Int, method: Int, body: ByteArray, format: Int): Boolean {
        if (!active(attempt)) return false
        val socket = (if (category == 4) attempt.audio else attempt.control) ?: return false
        val key = attempt.cipher ?: return false
        if (body.size > IccoaControl.MAX_FRAME_SIZE - 56) return false
        val copy = body.copyOf()
        try {
            (if (category == 4) attempt.audioSender else attempt.controlSender).execute {
                try {
                    if (!active(attempt)) return@execute
                    val frame = IccoaControl.frame(category, method, 0, key.encrypt(copy), attempt.sequence.getAndIncrement(), format)
                    if (category == 4) attempt.audioWritingAt = System.nanoTime() else attempt.controlWritingAt = System.nanoTime()
                    socket.getOutputStream().run { write(frame); flush() }
                    if (category == 1 && method == 1 && !attempt.firstHeartbeatWritten) {
                        attempt.firstHeartbeatWritten = true
                        DiagLog.i("ICCOA", "CONTROL first-heartbeat written")
                    } else if (category == 1 && method != 1) {
                        DiagLog.i("ICCOA", "CONTROL written method=$method bytes=${copy.size}")
                    }
                } catch (e: Exception) {
                    if (active(attempt)) DiagLog.w("ICCOA", "SEND category=$category method=$method ${IccoaTransportError.describe(e)}")
                    fail(attempt, "Канал ICCOA $category закрыт (${e.javaClass.simpleName})", transportFailure = true)
                } finally {
                    copy.fill(0)
                    if (category == 4) attempt.audioWritingAt = 0 else attempt.controlWritingAt = 0
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            copy.fill(0)
            // Audio backpressure drops one packet; an exhausted CONTROL send
            // queue is a broken transport, NOT a PIN/Quick authentication
            // rejection. Route through the existing 3-attempt bounded
            // transport-recovery path without replaying credentials.
            if (category != 4 && active(attempt))
                fail(attempt, "Переполнена очередь управления ICCOA", transportFailure = true)
            return false
        }
    }

    private fun bluetoothCallback(attempt: Attempt) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = post(attempt) {
            if (gatt !== attempt.gatt) return@post
            DiagLog.i("ICCOA", "GATT connection status=$status state=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.requestMtu(512)) discover(attempt, gatt)
                later(attempt, 2_000) { discover(attempt, gatt) }
            } else if (!attempt.wifiRequested && (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED)) {
                fail(attempt, "Соединение Bluetooth ICCOA закрыто (status=$status)", transportFailure = true)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = post(attempt) {
            if (gatt !== attempt.gatt) return@post
            DiagLog.i("ICCOA", "GATT MTU=$mtu status=$status")
            discover(attempt, gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = post(attempt) {
            if (gatt !== attempt.gatt || attempt.servicesHandled) return@post
            DiagLog.i("ICCOA", "GATT services status=$status")
            try {
                require(status == BluetoothGatt.GATT_SUCCESS)
                val service = gatt.getService(SERVICE) ?: error("ICCOA service missing")
                val incoming = service.getCharacteristic(SERVER_INFO) ?: error("Server info missing")
                require(service.getCharacteristic(CLIENT_INFO) != null)
                require(gatt.setCharacteristicNotification(incoming, true))
                attempt.servicesHandled = true
                // The observed Carlink bootstrap enables local notification routing and
                // proceeds directly to CLIENT_INFO. XA60 stalls on the extra CCCD write;
                // do not introduce an ATT operation absent from that exchange.
                DiagLog.i("ICCOA", "GATT ICCOA channel ready; checking remembered-car credentials")
                val pinned = if (tryRememberedPin && !forceCurrentPin && trustedCarName.isNotBlank())
                    trustedCarKey.load(attempt.profile.id, trustedCarName) else null
                if (pinned != null) {
                    attempt.pinReady = true
                    DiagLog.i("ICCOA", "Quick Auth: verified car key found, no old PIN will be sent")
                    submitQuick(attempt, pinned)
                } else {
                    // The HU issues a fresh six-digit PIN for every NORMAL pairing.
                    // An older encrypted PIN must never be sent automatically just
                    // because no trust key has been pinned yet.
                    requestPin(attempt)
                }
            } catch (e: Exception) { fail(attempt, "Устройство не предоставило канал ICCOA (${e.javaClass.simpleName})") }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = post(attempt) {
            if (gatt !== attempt.gatt) return@post
            DiagLog.i("ICCOA", "GATT characteristic write uuid=${characteristic.uuid} status=$status")
            if (gatt === attempt.gatt && characteristic.uuid == CLIENT_INFO && status != BluetoothGatt.GATT_SUCCESS && !attempt.wifiRequested) {
                if (attempt.quickMode) {
                    fail(attempt, "QUICK: ошибка Bluetooth (status=$status)", transportFailure = true)
                } else if (attempt.reusingPin) {
                    // ATT write failure cannot prove that the stored PIN is bad.
                    // Keep credentials for the next bounded transport attempt.
                    fail(attempt, "Ошибка записи сохранённого кода по Bluetooth (status=$status)",
                        transportFailure = true)
                } else requestPin(attempt, "Ошибка записи кода ICCOA (status=$status). Введите текущий PIN")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == SERVER_INFO) receiveBootstrap(attempt, gatt, value.copyOf())
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            if (characteristic.uuid == SERVER_INFO) receiveBootstrap(attempt, gatt, value.copyOf())
        }
    }

    private fun discover(attempt: Attempt, gatt: BluetoothGatt) {
        if (gatt !== attempt.gatt || attempt.servicesRequested) return
        attempt.servicesRequested = true
        try {
            if (!gatt.discoverServices()) fail(attempt, "Не удалось запросить службы ICCOA")
        } catch (e: Exception) { fail(attempt, "Ошибка служб Bluetooth (${e.javaClass.simpleName})") }
    }

    private fun requestPin(attempt: Attempt, message: String = "Введите шестизначный код с экрана автомобиля") {
        attempt.pin = null
        attempt.reusingPin = false
        attempt.quickMode = false
        attempt.pinnedCarIdentity = null
        attempt.pinReady = true
        status(attempt, message)
        onPinRequired()
    }

    private fun receiveBootstrap(attempt: Attempt, gatt: BluetoothGatt, fragment: ByteArray) = post(attempt) {
        try {
            if (gatt !== attempt.gatt || attempt.wifiRequested ||
                (attempt.pin == null && !attempt.quickMode)) return@post
            DiagLog.i("ICCOA", "GATT server-info fragment received: ${fragment.size} bytes")
            if (fragment.firstOrNull() == '{'.code.toByte()) attempt.serverInfo.reset()
            require(attempt.serverInfo.size() + fragment.size <= 4096)
            attempt.serverInfo.write(fragment)
            if (fragment.toString(Charsets.UTF_8).trimEnd().lastOrNull() != '}') return@post
            val raw = attempt.serverInfo.toByteArray()
            val info = try { JSONObject(raw.toString(Charsets.UTF_8)) } finally { raw.fill(0); attempt.serverInfo.reset() }
            require(info.optInt("type", 1001) == 1002) {
                "P2P_UNSUPPORTED"
            }
            joinWifi(attempt, info)
        } catch (e: Exception) {
            fail(attempt, if (e.message == "P2P_UNSUPPORTED")
                "Автомобиль запросил Wi-Fi Direct. Эта версия ICCOA поддерживает только режим точки доступа (AP)"
            else "Некорректные параметры Wi-Fi ICCOA (${e.javaClass.simpleName})")
        } finally { fragment.fill(0) }
    }

    private fun joinWifi(attempt: Attempt, info: JSONObject) {
        val specifier = WifiNetworkSpecifier.Builder().setSsid(info.getString("ssid"))
            .setWpa2Passphrase(info.getString("psk")).apply {
                val mac = info.optString("mac")
                if (mac.isNotBlank()) setBssid(MacAddress.fromString(mac))
            }.build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = post(attempt) {
                // A queued auth worker for the previous Network must not monopolize
                // authStarted when Android replaces the Wi-Fi handle before TCP bind.
                if (attempt.network != network) attempt.authStarted = false
                attempt.network = network
                attempt.networkGate.onAvailable(network)
                // Link properties may not yet exist in onAvailable; onLinkPropertiesChanged retries.
                connectivity.getLinkProperties(network)?.let { networkReady(attempt, network, it) }
            }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = post(attempt) {
                if (attempt.networkGate.isCurrent(network)) networkReady(attempt, network, properties)
            }
            // May mean the owner declined Android's Wi-Fi consent prompt.
            // Do not repeat permission prompts automatically.
            override fun onUnavailable() { fail(attempt, "Подключение к Wi-Fi автомобиля отменено или истёк тайм-аут") }
            override fun onLost(network: Network) = post(attempt) {
                if (attempt.networkGate.onLost(network))
                    fail(attempt, "Соединение с Wi-Fi автомобиля потеряно", transportFailure = true)
            }
        }
        attempt.wifiRequested = true
        attempt.networkCallback = callback
        status(attempt, "Подключение к Wi-Fi автомобиля. Подтвердите запрос Android")
        try {
            connectivity.requestNetwork(request, callback, 60_000)
        } catch (e: SecurityException) {
            fail(attempt, "Android запретил подключение к Wi-Fi автомобиля (SecurityException)")
            return
        } catch (e: Exception) {
            fail(attempt, "Не удалось запросить Wi-Fi автомобиля (${e.javaClass.simpleName})", transportFailure = true)
            return
        }
        later(attempt, 65_000) {
            if (!attempt.authStarted) fail(attempt, if (attempt.hasWifiAddress)
                "Wi-Fi автомобиля подключён, но не получен проверенный адрес шлюза"
            else "Не получен IPv4-адрес Wi-Fi автомобиля", transportFailure = true)
        }
    }

    private fun scheduleGatewayRetry(attempt: Attempt, network: Network) {
        if (!attempt.networkGate.isCurrent(network) || attempt.authStarted ||
            !attempt.gatewayRetry.schedule(network)) return
        if (attempt.gatewayWaitStatusNetwork != network) {
            attempt.gatewayWaitStatusNetwork = network
            status(attempt, "Wi-Fi подключён. Ожидание адреса шлюза автомобиля…")
        }
        later(attempt, 1_000) {
            // A's old timer must NOT steal B's pending retry.
            if (!attempt.gatewayRetry.consume(network) ||
                !attempt.networkGate.isCurrent(network) || attempt.authStarted) return@later
            connectivity.getLinkProperties(network)?.let { networkReady(attempt, network, it) }
                ?: scheduleGatewayRetry(attempt, network)
        }
    }

    private fun networkReady(attempt: Attempt, network: Network, properties: LinkProperties) {
        if (!attempt.networkGate.isCurrent(network) || attempt.authStarted) return
        val link = properties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return
        val address = link.address as Inet4Address
        val local = address.hostAddress ?: return
        attempt.hasWifiAddress = true
        val routed = properties.routes.firstOrNull { it.hasGateway() && it.gateway is Inet4Address }?.gateway
        // On concurrent Wi-Fi Android returns this requesting app's specifier connection.
        // Still reject a stale DHCP snapshot unless its local address/subnet match this Network.
        @Suppress("DEPRECATION")
        val fallback = if (routed == null) runCatching {
            context.getSystemService(WifiManager::class.java)?.dhcpInfo?.let {
                IccoaWifiGateway.fromDhcp(address, link.prefixLength, it.ipAddress, it.gateway)
            }
        }.getOrNull() else null
        val peer = (routed ?: fallback)?.hostAddress
        if (peer == null) {
            scheduleGatewayRetry(attempt, network)
            return
        }
        attempt.gatewayRetry.clear()
        DiagLog.i("ICCOA", "Wi-Fi gateway source=${if (routed != null) "routes" else "validated-dhcp"}")
        val pin = attempt.pin
        val pinned = attempt.pinnedCarIdentity?.copyOf()
        if (pin == null && pinned == null)
            return fail(attempt, "Нет подтверждённого способа авторизации ICCOA")
        attempt.authStarted = true
        attempt.network = network
        // Keep the PIN across Network replacement within this attempt only.
        attempt.pin = IccoaNetworkPinLifecycle.afterAuthQueued(pin)
        status(attempt, "Проверка подлинности ICCOA…")
        worker(attempt) { authenticate(attempt, network, local, peer, pin, pinned) }
    }

    private fun authenticate(attempt: Attempt, network: Network, local: String, peer: String,
                             pin: String?, pinned: ByteArray?) {
        var stage = "tcp-bind"
        fun progress(value: String) {
            stage = value
            if (active(attempt)) DiagLog.i("ICCOA", "AUTH $value")
        }
        try {
            val socket = Socket()
            if (!track(attempt, socket)) return
            if (!attempt.networkGate.bindIfCurrent(network) { network.bindSocket(socket) }) {
                socket.close()
                return // Superseded before TCP; the current Network owns authentication.
            }
            progress("tcp-connect")
            socket.connect(InetSocketAddress(peer, 57209), 8_000)
            // A replacement Network can arrive while TCP connect is blocked.
            // Never send the signed ICCOA auth request over that stale socket.
            if (!attempt.networkGate.isCurrent(network)) {
                socket.close()
                return
            }
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            progress("build-request")
            val auth = IccoaAuth(pin, attempt.clientId, Build.MODEL, IccoaClientIdentity.load(), pinned)
            progress("send-request")
            socket.getOutputStream().run { write(IccoaControl.frame(3, 1, 1, auth.request(), 1)); flush() }
            progress("read-response")
            val response = IccoaControl.read(socket.getInputStream()) { header ->
                if (active(attempt)) DiagLog.i("ICCOA", "AUTH header $header")
            }
            // The reference accepts the first auth-lane response without specifying method/type.
            // Its protobuf transcript is still authenticated below before any listener is exposed.
            if (response.category != 3 || response.format != 1) throw IccoaHandshakeException(IccoaHandshakeException.Reason.RESPONSE_LANE)
            val key = auth.accept(response.body, ::progress)
            val verifiedCarIdentity = auth.verifiedCarIdentity
                ?: throw IccoaHandshakeException(IccoaHandshakeException.Reason.IDENTITY_LENGTH)
            // A Normal response must not silently overwrite an existing trusted car key.
            if (pinned == null && trustedCarName.isNotBlank()) {
                val oldKey = trustedCarKey.load(attempt.clientId, trustedCarName)
                if (oldKey != null && !MessageDigest.isEqual(oldKey, verifiedCarIdentity))
                    throw IccoaHandshakeException(IccoaHandshakeException.Reason.TRUSTED_IDENTITY_MISMATCH)
            }
            synchronized(attempt) {
                if (!active(attempt)) return
                if (!attempt.networkGate.commitIfCurrent(network) { attempt.cipher = key }) return
                // Verified auth no longer needs the PIN; never carry it into
                // later reconnects or a new authentication episode.
                attempt.pin = IccoaNetworkPinLifecycle.afterVerifiedAuth()
            }
            progress("open-control-listeners")
            for (port in intArrayOf(57219, 57229, 57239, 57249, 57259)) listen(attempt, local, peer, port)
            if (!active(attempt) || !attempt.networkGate.isCurrent(network)) return
            // External callbacks may acquire their owner's lifecycle lock. Never hold ours here.
            progress("open-media-listeners")
            onReady(network, peer)
            if (!active(attempt) || !attempt.networkGate.isCurrent(network)) return
            progress("send-confirm")
            socket.getOutputStream().run { write(IccoaControl.frame(3, 3, 0, auth.confirm(key, local), 2)); flush() }
            // Do NOT save just because AuthConfirm was written. Wait for the
            // car to open the authenticated CONTROL lane as additional proof.
            synchronized(attempt) {
                if (pinned == null) attempt.pendingTrustedCarIdentity = verifiedCarIdentity.copyOf()
            }
            if (attempt.trustedCommitGate.onConfirmSent()) confirmTrustedCarAfterControl(attempt)
            status(attempt, "ICCOA: аутентификация завершена, ожидание каналов автомобиля…")
            later(attempt, 25_000) {
                if (attempt.control == null) fail(attempt, "Автомобиль не открыл канал управления ICCOA", transportFailure = true)
            }
            watchWrites(attempt)
            // Auth is a bootstrap lane: receivers may close it after confirmation.
            socket.soTimeout = 0
            try { while (active(attempt)) IccoaControl.read(socket.getInputStream()) } catch (_: Exception) { }
            socket.close()
        } catch (e: Exception) {
            if (!active(attempt)) return
            // An old Network worker must not fail the replacement connection or
            // clear its remembered PIN after Android switches Wi-Fi handles.
            if (!attempt.networkGate.isCurrent(network)) return
            if (attempt.reusingPin) {
                if (IccoaPinCachePolicy.shouldClearAfterAuthFailure(e)) {
                    trustedPin.clear()
                    DiagLog.w("ICCOA", "Remembered-car PIN rejected by authenticated protocol; cached credential cleared")
                } else {
                    DiagLog.w("ICCOA", "Remembered-car handshake/transport failed; cached PIN retained, no automatic auth retry")
                }
            }
            // Exception messages may contain peer data. Log only our fixed codes and stack locations.
            val reason = (e as? IccoaHandshakeException)?.diagnostic ?: e.javaClass.simpleName
            val location = e.stackTrace.firstOrNull { it.className.startsWith("app.ft.iccoa.") }
                ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
            DiagLog.w("ICCOA", "AUTH failed stage=$stage reason=$reason at=$location")
            val hint = when ((e as? IccoaHandshakeException)?.reason) {
                IccoaHandshakeException.Reason.PIN_MAC -> "Код и ответ автомобиля не совпали. Начните новое подключение с текущим кодом"
                IccoaHandshakeException.Reason.RESPONSE_RESULT -> when (e.result) {
                    1L -> "Автомобиль запросил новое подключение с кодом"
                    2L -> "Автомобиль запросил подтверждение на своём экране"
                    3L -> "Автомобиль сообщил о внутренней ошибке"
                    4L -> "Автомобиль отклонил режим подключения"
                    else -> "Автомобиль отклонил подключение"
                }
                else -> "Отправьте отчёт из FT"
            }
            val quickRejected = pinned != null && e is IccoaHandshakeException &&
                e.reason == IccoaHandshakeException.Reason.RESPONSE_RESULT && (e.result == 1L || e.result == 2L)
            fail(attempt, "Сбой ICCOA на этапе $stage ($reason). $hint",
                transportFailure = (stage == "tcp-bind" || stage == "tcp-connect") &&
                    e !is IccoaHandshakeException, quickNormalFallback = quickRejected)
        }
    }

    /**
     * Both signed AuthConfirm and authenticated CONTROL acceptance are needed
     * before a first Normal session may pin the server public key. An OEM
     * Quick reconnection proves this same identity by signature, not by PIN.
     */
    private fun confirmTrustedCarAfterControl(attempt: Attempt) {
        if (!active(attempt)) return
        if (attempt.quickMode) {
            DiagLog.i("ICCOA", "QUICK_AUTH_PINLESS_CONFIRMED: signed response + CONTROL")
            return
        }
        val carKey = synchronized(attempt) {
            val saved = attempt.pendingTrustedCarIdentity
            attempt.pendingTrustedCarIdentity = null
            saved
        }
        if (trustedCarName.isBlank()) {
            DiagLog.w("ICCOA", "TRUSTED_CAR_IDENTITY_NOT_SAVED: missing approved trust scope")
            return
        }
        if (carKey != null) {
            val pinned = trustedCarKey.saveIfAbsent(attempt.profile.id, trustedCarName, carKey)
            DiagLog.i("ICCOA", if (pinned) "Car authentication key PINNED after CONTROL" else
                "Car authentication key NOT pinned: mismatch or storage unavailable")
            carKey.fill(0)
        }
        // Toyota shows a new six-digit PIN for each NORMAL connection. Keep
        // the stable verified OEM server public key, not an expired PIN.
        trustedPin.clear()
    }

    private fun listen(attempt: Attempt, local: String, peer: String, port: Int) {
        val lane = IccoaLaneServer(port, InetSocketAddress(local, port), InetAddress.getByName(peer),
            onConnected = { socket ->
                synchronized(attempt) {
                    if (active(attempt)) {
                        if (port == IccoaLaneServer.CONTROL_PORT) {
                            attempt.control = socket
                            val heartbeat = IccoaHeartbeat(
                                send = { body -> send(attempt, 1, 1, body, 1) },
                                schedule = { delay, action -> later(attempt, delay, action) })
                            attempt.resources += heartbeat
                            DiagLog.i("ICCOA", "CONTROL starting heartbeat on accept")
                            heartbeat.start()
                        }
                        if (port == IccoaLaneServer.AUDIO_PORT) attempt.audio = socket
                    }
                }
                if (port == IccoaLaneServer.CONTROL_PORT && active(attempt)) {
                    if (attempt.trustedCommitGate.onControlReady()) confirmTrustedCarAfterControl(attempt)
                    onControlReady()
                }
            },
            onDisconnected = { socket ->
                val lostControl = synchronized(attempt) {
                    val wasControl = attempt.control === socket
                    if (wasControl) attempt.control = null
                    if (attempt.audio === socket) attempt.audio = null
                    wasControl
                }
                if (lostControl && active(attempt))
                    fail(attempt, "ICCOA CONTROL channel disconnected", transportFailure = true)
            },
            onFrame = { frame -> handleFrame(attempt, frame) },
            log = { message -> if (active(attempt)) DiagLog.i("ICCOA", message) })
        if (!track(attempt, lane)) return
        worker(attempt) {
            try {
                lane.run()
            } catch (e: Exception) { fail(attempt, "Канал ICCOA $port закрыт (${e.javaClass.simpleName})", transportFailure = true) }
        }
    }

    private fun handleFrame(attempt: Attempt, frame: IccoaControl.Frame) {
        if (!active(attempt)) return
        val key = attempt.cipher ?: error("Session key unavailable")
        // Empty/plain protocol messages exist in the reference; encrypted envelopes fail closed.
        val body = if (IccoaControl.isEncrypted(frame.body)) key.decrypt(frame.body) else frame.body
        if (!active(attempt)) return
        if (IccoaCloseEvent.isRemoteDisconnect(frame.category, frame.method)) {
            fail(attempt, "Автомобиль завершил соединение ICCOA", transportFailure = true)
            return
        }
        if (frame.category != 1 || frame.method != 1) onMessage(frame.category, frame.method, body)
    }

    private fun watchWrites(attempt: Attempt): Unit = post(attempt) {
        val now = System.nanoTime()
        if (listOf(attempt.controlWritingAt, attempt.audioWritingAt).any {
                it > 0 && now - it > TimeUnit.SECONDS.toNanos(5)
            }) {
            // Socket write timeout is a confirmed transport hang. Without
            // the typed transport action, the service permanently STOPped
            // even though its 3-attempt reconnect guard was already present.
            fail(attempt, "Тайм-аут отправки данных ICCOA", transportFailure = true)
        } else {
            later(attempt, 2_000) { watchWrites(attempt) }
        }
    }

    private fun active(attempt: Attempt) = current === attempt && !attempt.closed
    private fun post(attempt: Attempt, action: () -> Unit) {
        main.post {
            if (active(attempt)) try { action() } catch (e: Exception) {
                fail(attempt, "Ошибка подключения ICCOA (${e.javaClass.simpleName})")
            }
        }
    }
    private fun later(attempt: Attempt, delay: Long, action: () -> Unit) {
        main.postAtTime({ if (active(attempt)) action() }, attempt, android.os.SystemClock.uptimeMillis() + delay)
    }
    private fun status(attempt: Attempt, message: String) = post(attempt) {
        DiagLog.i("ICCOA", message)
        onStatus(message)
    }
    private fun worker(attempt: Attempt, action: () -> Unit) {
        try { attempt.workers.execute { if (active(attempt)) action() } } catch (_: RejectedExecutionException) { }
    }
    private fun track(attempt: Attempt, resource: Closeable): Boolean = synchronized(attempt) {
        if (!active(attempt)) { runCatching { resource.close() }; false }
        else { attempt.resources += resource; true }
    }

    private fun fail(attempt: Attempt, message: String, transportFailure: Boolean = false,
                     quickNormalFallback: Boolean = false) = post(attempt) {
        synchronized(this) {
            if (!active(attempt)) return@post
            current = null
            close(attempt)
        }
        onClosed(IccoaCloseEvent.classify(message, transportFailure, quickNormalFallback))
    }

    private fun close(attempt: Attempt) {
        synchronized(attempt) {
            attempt.closed = true
            attempt.cipher = null
            attempt.pin = null
            attempt.pendingTrustedCarIdentity = null
            attempt.pinnedCarIdentity = null
            attempt.quickMode = false
            attempt.trustedCommitGate.cancel()
            attempt.resources.forEach { runCatching { it.close() } }
            attempt.resources.clear()
            attempt.control = null
            attempt.audio = null
        }
        main.removeCallbacksAndMessages(attempt)
        attempt.workers.shutdownNow()
        attempt.controlSender.shutdownNow()
        attempt.audioSender.shutdownNow()
        val releaseAndroidResources = {
            runCatching { attempt.gatt?.disconnect() }
            runCatching { attempt.gatt?.close() }
            attempt.gatt = null
            attempt.networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            attempt.networkCallback = null
            attempt.network = null
            attempt.networkGate.clear()
            attempt.gatewayRetry.clear()
            attempt.serverInfo.reset()
            DiagLog.i("ICCOA", "Session resources released: Bluetooth, Wi-Fi request and sockets")
        }
        // Main-thread callers must release Android handles before a new scan/attempt starts.
        if (Looper.myLooper() == main.looper) releaseAndroidResources()
        else main.post { releaseAndroidResources() }
    }

    companion object {
        private val SERVICE = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789100")
        private val CLIENT_INFO = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789101")
        private val SERVER_INFO = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789102")
    }
}
