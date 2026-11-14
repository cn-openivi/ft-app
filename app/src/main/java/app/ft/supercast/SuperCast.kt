package app.ft.supercast

import android.os.Bundle
import android.os.IBinder
import android.view.Surface
import app.ft.FTApp
import app.ft.core.DiagLog
import app.ft.core.Root
import app.ft.server.IFtClient
import app.ft.server.IFtServer
import app.ft.supercast.adb.WirelessAdbDiscovery
import app.ft.supercast.adb.WirelessAdbEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

object SuperCast {
    private const val TAG = "SuperCast"

    val token: String
        get() {
            val stored = FTApp.instance.prefs.supercastToken
            return if (stored.isNotEmpty()) stored else freshToken()
        }
    @Volatile private var server: IFtServer? = null
    @Volatile var helperUid: Int = -1
    /** True only while shell helper confirmed an open physical touchscreen event stream. */
    @Volatile var physicalTouchMonitorActive = false

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    enum class StartReason { NONE, NEED_PAIRING, DEBUGGING_OFF, TLS_REJECTED, CONNECT_FAILED, HELPER_UNREACHABLE, LEGACY_DISABLED }
    private val _startReason = MutableStateFlow(StartReason.NONE)
    val startReason: StateFlow<StartReason> = _startReason

    val client: IFtClient = object : IFtClient.Stub() {
        override fun onServer(bound: IFtServer?, sent: String?) {
            if (sent != token) {
                DiagLog.w(TAG, "a helper offered itself with the wrong token, ignoring it")
                return
            }
            bindHelper(bound)
            if (_ready.value && app.ft.core.MirrorPanelSaver.isActive)
                later { monitorPhoneTouches(true) }
            if (_ready.value) DiagLog.i(TAG, "the SuperCast helper is connected")
            else DiagLog.w(TAG, "the SuperCast helper did not supply a live Binder")
        }

        override fun onEvent(event: Bundle?) {
            when (event?.getString("event")) {
                "phone-touch" -> app.ft.core.MirrorPanelSaver.onPhysicalTouch(FTApp.instance)
                "phone-touch-monitor-ready" -> physicalTouchMonitorActive = true
                "phone-touch-monitor-unavailable" -> physicalTouchMonitorActive = false
                else -> DiagLog.i(TAG, "helper event: ${event?.getString("event")}")
            }
        }

        override fun onAudio(pcm: ByteArray?, sampleRate: Int, channels: Int) = Unit
    }

    fun freshToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val made = bytes.joinToString("") { "%02x".format(it) }
        FTApp.instance.prefs.supercastToken = made
        return made
    }

    private var deathBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    @Synchronized
    private fun bindHelper(bound: IFtServer?) {
        drop()
        if (bound == null) return
        val binder = bound.asBinder()
        val listener = IBinder.DeathRecipient {
            synchronized(this) {
                if (deathBinder === binder) {
                    DiagLog.w(TAG, "the SuperCast helper process died")
                    drop()
                }
            }
        }
        val linked = runCatching { binder.linkToDeath(listener, 0); true }.getOrDefault(false)
        if (!linked) return
        if (!binder.isBinderAlive) {
            runCatching { binder.unlinkToDeath(listener, 0) }
            return
        }
        deathBinder = binder
        deathRecipient = listener
        server = bound
        _ready.value = true
    }

    @Synchronized
    fun drop() {
        physicalTouchMonitorActive = false
        val binder = deathBinder
        val recipient = deathRecipient
        deathBinder = null
        deathRecipient = null
        server = null
        _ready.value = false
        if (binder != null && recipient != null)
            runCatching { binder.unlinkToDeath(recipient, 0) }
    }

    fun call(request: Bundle): Bundle? {
        val bound = server ?: return null
        request.putString("token", token)
        return runCatching { bound.call(request) }
            .onFailure { DiagLog.w(TAG, "the helper did not answer: ${it.message}"); drop() }
            .getOrNull()
    }

    private fun callWith(surface: Surface, request: Bundle): Bundle? {
        val bound = server ?: return null
        request.putString("token", token)
        return runCatching { bound.withSurface(surface, request) }
            .onFailure { DiagLog.w(TAG, "the helper did not answer: ${it.message}"); drop() }
            .getOrNull()
    }

    private fun ask(what: String, build: Bundle.() -> Unit = {}): Bundle? =
        call(Bundle().apply { putString("do", what); build() })

    fun ping(): Boolean = ask("ping")?.getString("pong") == "ft"

    fun openPane(name: String, surface: Surface, width: Int, height: Int, dpi: Int, packageName: String?): Int {
        val answer = callWith(surface, Bundle().apply {
            putString("do", "open")
            putString("name", name)
            putInt("width", width)
            putInt("height", height)
            putInt("dpi", dpi)
            if (!packageName.isNullOrEmpty()) putString("package", packageName)
        }) ?: return -1
        return answer.getInt("display", -1)
    }

    fun closePane(name: String) {
        ask("close") { putString("name", name) }
    }

    fun detachPane(name: String) {
        ask("detach") { putString("name", name) }
    }

    fun launch(name: String, packageName: String): Boolean =
        ask("launch") { putString("name", name); putString("package", packageName) }?.getBoolean("ok") == true

    private val queue = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ft-supercast").apply { isDaemon = true }
    }

    fun later(block: () -> Unit) {
        runCatching { queue.execute { runCatching(block).onFailure { DiagLog.w(TAG, "helper call failed: ${it.message}") } } }
    }

    private val touchThread = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ft-supercast-touch").apply { isDaemon = true }
    }
    private val waiting = java.util.concurrent.atomic.AtomicInteger(0)

    fun touch(name: String, action: Int, x: Float, y: Float, fromWidth: Int, fromHeight: Int) {
        if (server == null) return
        val moving = action == android.view.MotionEvent.ACTION_MOVE
        if (moving && waiting.get() > 2) return
        waiting.incrementAndGet()
        runCatching {
            touchThread.execute {
                try {
                    ask("touch") {
                        putString("name", name)
                        putInt("action", action)
                        putFloat("x", x)
                        putFloat("y", y)
                        putInt("from", fromWidth)
                        putInt("fromHeight", fromHeight)
                    }
                } finally {
                    waiting.decrementAndGet()
                }
            }
        }.onFailure { waiting.decrementAndGet() }
    }

    fun key(name: String, keyCode: Int) {
        ask("key") { putString("name", name); putInt("key", keyCode) }
    }

    fun apps(): List<Pair<String, String>> {
        val answer = ask("apps") ?: return emptyList()
        val packages = answer.getStringArrayList("packages") ?: return emptyList()
        val labels = answer.getStringArrayList("labels") ?: return emptyList()
        return packages.mapIndexed { index, pkg -> pkg to (labels.getOrNull(index) ?: pkg) }
    }

    fun phoneScreen(on: Boolean) {
        ask("screen") { putBoolean("on", on) }
    }

    fun manageScreen(darkenByItself: Boolean) {
        ask("screen") { putBoolean("on", false); putBoolean("auto", darkenByItself) }
    }

    fun powerPressed() {
        ask("power")
    }

    /** Authenticated helper monitoring, enabled only while FT is dimming a mirrored phone. */
    fun monitorPhoneTouches(on: Boolean) {
        if (!on) physicalTouchMonitorActive = false
        if (server != null) ask("phone-touch-monitor") { putBoolean("on", on) }
    }

    fun keepAwake(on: Boolean, wake: Boolean = false) {
        ask("awake") { putBoolean("on", on); putBoolean("wake", wake) }
    }

    fun stopAll() {
        ask("stop")
        drop()
    }

    @Synchronized fun start(context: android.content.Context): Boolean {
        if (_ready.value && ping()) {
            _startReason.value = StartReason.NONE
            return true
        }
        if (_ready.value) drop()
        _startReason.value = StartReason.NONE
        WirelessAdbEngine.markStage(WirelessAdbEngine.Stage.IDLE)
        helperUid = -1
        val fresh = freshToken()
        if (Root.ensure()) {
            if (Helper.startWithRoot(context, fresh) { _ready.value && ping() }) {
                FTApp.instance.prefs.supercastWay = WAY_ROOT
                _startReason.value = StartReason.NONE
                DiagLog.i(TAG, "apps on the car are ready over root")
                return true
            }
        }

        // Pairing GUID was authenticated by SPAKE2. Discover a fresh connect
        // port belonging to the SAME local Android device on every attempt.
        if (WirelessAdbEngine.isPaired(context)) {
            val pairedGuid = requireNotNull(WirelessAdbEngine.pairedGuid(context))
            WirelessAdbEngine.markStage(WirelessAdbEngine.Stage.CONNECT_DISCOVERY)
            val found = runBlocking {
                WirelessAdbDiscovery.findConnect(context.applicationContext, pairedGuid, timeoutMs = 5_000)
            }
            if (found == null) {
                _startReason.value = StartReason.DEBUGGING_OFF
            } else {
                var transportError: Throwable? = null
                val dispatched = runCatching {
                    runBlocking { WirelessAdbEngine.startHelper(context.applicationContext, found, fresh) }
                }.onFailure {
                    transportError = it
                    DiagLog.w(TAG, WirelessAdbEngine.sanitizedFailure(it))
                }.getOrDefault(false)

                if (dispatched && waitForHandshake() && ping() && helperUid == 2000) {
                    if (WirelessAdbEngine.commitVerifiedConnect(context.applicationContext)) {
                        FTApp.instance.prefs.supercastWay = WAY_TLS
                        _startReason.value = StartReason.NONE
                        return true
                    }
                    DiagLog.w(TAG, "ADB_TLS_FAILURE stage=COMMIT_VERIFIED_PEER")
                    drop()
                    _startReason.value = StartReason.TLS_REJECTED
                } else {
                    _startReason.value = when {
                        dispatched -> StartReason.HELPER_UNREACHABLE
                        transportError?.let { WirelessAdbEngine.hasCertificateMismatch(it) } == true ->
                            StartReason.TLS_REJECTED
                        else -> StartReason.CONNECT_FAILED
                    }
                    if (!dispatched && transportError == null) {
                        DiagLog.w(TAG, "ADB_TLS_FAILURE stage=" + WirelessAdbEngine.stage.name + " result=NOT_DISPATCHED")
                    }
                }
            }
        } else {
            _startReason.value = StartReason.NEED_PAIRING
        }

        // Plain TCP :5555 is an EXPLICIT expert fallback.
        if (!FTApp.instance.prefs.supercastLegacyAdb) return false
        val legacy = Helper.startWithAdb(context, fresh)
        if (legacy == Helper.Adb.STARTED && waitForHandshake() && ping()) {
            FTApp.instance.prefs.supercastWay = WAY_BRIDGE
            _startReason.value = StartReason.NONE
            return true
        }
        return false
    }

    private fun waitForHandshake(): Boolean {
        repeat(24) {
            if (_ready.value) return true
            Thread.sleep(250)
        }
        return _ready.value
    }

    fun startOverBridge(context: android.content.Context): Boolean = start(context)

    const val WAY_NONE = 0
    const val WAY_ROOT = 1
    const val WAY_BRIDGE = 2
    const val WAY_TLS = 3
}
