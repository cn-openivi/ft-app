package app.ft.supercast.adb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.ft.MainActivity
import app.ft.R
import app.ft.supercast.SuperCast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * One-time, foreground, user-initiated Android 11+ wireless ADB pairing.
 *
 * RemoteInput is attached to the notification shown ABOVE the open Android
 * pairing-code Settings dialog. User never needs a second app window.
 */
class WirelessPairingService : Service() {
    enum class Phase { IDLE, SEARCHING, CODE_READY, PAIRING, CONNECTING, READY, FAILED }
    data class State(val phase: Phase, val reason: String? = null)

    companion object {
        private const val CHANNEL = "ft_wireless_pairing"
        private const val NOTIFICATION_ID = 1821
        private const val RESULT_ID = 1822
        private const val ACTION_START = "app.ft.pairing.START"
        private const val ACTION_CODE = "app.ft.pairing.CODE"
        private const val ACTION_STOP = "app.ft.pairing.STOP"
        private const val EXTRA_PORT = "pairing_port"
        private const val EXTRA_AUTO = "auto_code"
        private const val EXTRA_CODE = "pairing_code"
        private const val REMOTE_KEY = "ft_pairing_code"

        private val _status = MutableStateFlow(State(Phase.IDLE))
        val status: StateFlow<State> = _status
        @Volatile private var activePairingPort: Int? = null

        fun currentPort(): Int? = activePairingPort

        fun begin(context: Context, autoRead: Boolean): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                _status.value = State(Phase.FAILED, "unsupported")
                return false
            }
            val notificationsEnabled =
                (context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true) &&
                (Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            if (!notificationsEnabled && !autoRead) {
                _status.value = State(Phase.FAILED, "notifications_disabled")
                return false
            }
            return try {
                val intent = Intent(context, WirelessPairingService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_AUTO, autoRead)
                context.startForegroundService(intent)
                true
            } catch (_: Exception) {
                _status.value = State(Phase.FAILED, "background_restricted")
                false
            }
        }

        fun submitAutoCode(context: Context, port: Int, code: String) {
            if (!WirelessPairingCode.isValid(code)) return
            val intent = Intent(context, WirelessPairingService::class.java)
                .setAction(ACTION_CODE).putExtra(EXTRA_PORT, port).putExtra(EXTRA_CODE, code)
            runCatching { context.startService(intent) }
        }

        fun stopPairing(context: Context) {
            _status.value = State(Phase.IDLE)
            runCatching { context.startService(Intent(context, WirelessPairingService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var manager: NotificationManager
    private var scanner: WirelessAdbDiscovery? = null
    @Volatile private var pairingBusy = false
    private var deadlineJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.wireless_pair_channel), NotificationManager.IMPORTANCE_HIGH
        ).apply { setSound(null, null); enableVibration(false) })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (scanner != null) return START_NOT_STICKY
                if (!startForegroundSafely()) return START_NOT_STICKY
                _status.value = State(Phase.SEARCHING)
                val mdns = WirelessAdbDiscovery(this, WirelessAdbDiscovery.PAIRING)
                scanner = mdns
                mdns.start()
                // Limit the foreground discovery itself, not just code capture.
                deadlineJob?.cancel()
                deadlineJob = scope.launch {
                    delay(120_000L)
                    if (scanner === mdns) {
                        _status.value = State(Phase.FAILED, "timed_out")
                        manager.notify(RESULT_ID, statusNotification(null))
                        stopSelf()
                    }
                }
                if (intent.getBooleanExtra(EXTRA_AUTO, false)) {
                    WirelessPairingAutoCode.arm(this)
                }
                scope.launch {
                    mdns.endpoint.collect { endpoint ->
                        activePairingPort = endpoint?.port
                        if (!pairingBusy) {
                            _status.value = State(if (endpoint == null) Phase.SEARCHING else Phase.CODE_READY)
                            manager.notify(NOTIFICATION_ID, statusNotification(endpoint?.port))
                        }
                    }
                }
            }
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_KEY)?.toString()
                    ?: intent.getStringExtra(EXTRA_CODE).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                val current = activePairingPort
                if (!WirelessPairingCode.isValid(code) || port != current || pairingBusy) {
                    _status.value = State(Phase.FAILED, "expired_code")
                    manager.notify(NOTIFICATION_ID, statusNotification(current))
                    return START_NOT_STICKY
                }
                pairingBusy = true
                WirelessPairingAutoCode.cancel()
                _status.value = State(Phase.PAIRING)
                manager.notify(NOTIFICATION_ID, statusNotification(null))
                scope.launch {
                    val paired = runCatching { WirelessAdbEngine.pair(applicationContext, port, code) }
                        .getOrDefault(false)
                    if (!paired) {
                        pairingBusy = false
                        _status.value = State(Phase.FAILED, "pairing_failed")
                        manager.notify(NOTIFICATION_ID, statusNotification(activePairingPort))
                        return@launch
                    }
                    _status.value = State(Phase.CONNECTING)
                    manager.notify(NOTIFICATION_ID, statusNotification(null))
                    // SuperCast.start checks actual helper Binder+ping before reporting READY.
                    val ready = runCatching { SuperCast.start(applicationContext) }.getOrDefault(false)
                    _status.value = State(if (ready) Phase.READY else Phase.FAILED,
                        if (ready) null else "helper_failed")
                    finishPairing()
                }
            }
            ACTION_STOP -> {
                deadlineJob?.cancel()
                _status.value = State(Phase.IDLE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely(): Boolean {
        return try {
            val n = statusNotification(null)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(NOTIFICATION_ID, n)
            true
        } catch (_: Exception) {
            _status.value = State(Phase.FAILED, "foreground_denied")
            stopSelf()
            false
        }
    }

    private fun statusNotification(port: Int?): Notification {
        val phase = _status.value.phase
        val title = getString(R.string.wireless_pair_channel)
        val message = getString(when (phase) {
            Phase.IDLE, Phase.SEARCHING -> R.string.wireless_pair_search
            Phase.CODE_READY -> R.string.wireless_pair_enter_code
            Phase.PAIRING -> R.string.wireless_pair_in_progress
            Phase.CONNECTING -> R.string.wireless_pair_connecting
            Phase.READY -> R.string.wireless_pair_success
            Phase.FAILED -> R.string.wireless_pair_failed
        })
        val content = PendingIntent.getActivity(this, 1823,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(content)
            .setOnlyAlertOnce(port == null)
            .setOngoing(phase != Phase.READY && phase != Phase.FAILED)

        if (port != null && phase != Phase.PAIRING && phase != Phase.CONNECTING) {
            val reply = PendingIntent.getService(
                this, port,
                Intent(this, WirelessPairingService::class.java)
                    .setAction(ACTION_CODE).putExtra(EXTRA_PORT, port),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val input = RemoteInput.Builder(REMOTE_KEY)
                .setLabel(getString(R.string.wireless_pair_code_hint)).build()
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_edit, getString(R.string.wireless_pair_enter_code), reply
            ).addRemoteInput(input).build())
        }
        builder.addAction(Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.action_cancel),
            PendingIntent.getService(this, 1824,
                Intent(this, WirelessPairingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ).build())
        return builder.build()
    }

    private fun finishPairing() {
        deadlineJob?.cancel()
        val n = statusNotification(null)
        manager.notify(RESULT_ID, n)
        stopSelf()
    }

    override fun onDestroy() {
        deadlineJob?.cancel()
        WirelessPairingAutoCode.cancel()
        scanner?.stop()
        scanner = null
        activePairingPort = null
        if (_status.value.phase !in listOf(Phase.READY, Phase.FAILED)) {
            _status.value = State(Phase.IDLE)
        }
        scope.cancel()
        super.onDestroy()
    }
}
