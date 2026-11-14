package app.ft.iccoa

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.ft.FTApp
import app.ft.MainActivity
import app.ft.R
import app.ft.aa.AaHeadUnitService
import app.ft.core.CarProtocol
import app.ft.core.DiagLog
import app.ft.core.ProtoWriter
import app.ft.core.VehicleTelemetry
import app.ft.core.VehicleLocation
import app.ft.core.MotionSource
import app.ft.core.MotionKind
import app.ft.core.MotionVector
import app.ft.core.VehicleMotionTelemetry
import app.ft.dr.DrPathEvidence
import app.ft.core.VehicleTraceRecorder
import app.ft.core.NavigationInfo
import app.ft.projection.IccoaCalibrationScope
import app.ft.projection.CarTouchCalibration
import app.ft.projection.MirrorTouchCalibration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class IccoaState(
    val running: Boolean = false,
    val discovery: IccoaDiscoveryState = IccoaDiscoveryState(),
    val status: String = "",
    val pinRequired: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    /** Actual video path, never inferred from the user-selected preference. */
    val effectiveVideoMode: Int = 0,
    val videoFallbackReason: String? = null,
    val verifiedReceiverId: String? = null
)

/** Independent ICCOA connection, control and WFD lifecycle. */
class IccoaService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val lifecycle = Any()
    private val rememberedCar by lazy { IccoaRememberedCar(this) }
    private val bluetoothIdentityLink by lazy { IccoaBluetoothIdentityLinkStore(this) }
    private var lastBluetoothLinkSession = -1
    private var pendingCar: IccoaPeer? = null
    private var autoConnectAttempted = false
    private val dropRecovery = IccoaDropRecovery()
    private val seekPolicy = IccoaSeekPolicy()
    private fun startBackgroundScan() {
        discovery?.start(
            lowPower = true, knownToken = rememberedCar.savedReceiverTokenBytes(),
            useFilters = seekPolicy.useHardwareFilter()
        )
    }
    private val passiveSeek = Runnable {
        if (seekingAllowed()) {
            DiagLog.i("ICCOA", "Continuing low-power search for remembered car")
            startBackgroundScan()
        }
    }
    private fun seekingAllowed(): Boolean =
        FTApp.instance.prefs.carProtocol == CarProtocol.ICCOA &&
            IccoaSeekPolicy.eligible(
                enabled = FTApp.instance.prefs.autoConnect,
                running = _state.value.running,
                connected = _state.value.connected,
                connecting = _state.value.connecting,
                pinRequired = _state.value.pinRequired
            )

    private fun schedulePassiveSeek() {
        if (!seekingAllowed() || dropRecovery.active) return
        main.removeCallbacks(passiveSeek)
        val delayMs = seekPolicy.nextDelayMs()
        DiagLog.i("ICCOA", "Car not in range; low-power scan resumes in ${delayMs}ms, no global attempt limit")
        main.postDelayed(passiveSeek, delayMs)
    }
    private val recoveryScan = Runnable {
        if (dropRecovery.active && _state.value.running &&
            !_state.value.connecting && !_state.value.connected)
            startBackgroundScan()
    }
    private fun scheduleRecoveryScan(number: Int) {
        main.removeCallbacks(recoveryScan)
        main.removeCallbacks(passiveSeek)
        val delayMs = when (number) { 1 -> 0L; 2 -> 3_000L; else -> 6_000L }
        DiagLog.i("ICCOA", "Remembered-car recovery attempt $number/${IccoaDropRecovery.MAX_ATTEMPTS}")
        main.postDelayed(recoveryScan, delayMs)
    }
    @Volatile private var generation = 0
    private var discovery: IccoaDiscovery? = null
    @Volatile private var session: IccoaSession? = null
    @Volatile private var media: IccoaMediaTransport? = null
    private var projection: IccoaProjection? = null
    private var directVideo: IccoaDirectVideo? = null
    private var connectionVideoMode = 0
    private var microphone: IccoaMicrophone? = null
    private var microphoneHook: ((Boolean) -> Unit)? = null
    private var navigationHook: ((NavigationInfo) -> Unit)? = null
    private var latestNavigation = NavigationInfo()
    private var navigationRetries = 0
    private val navigationGate = IccoaControl11Gate()
    // Snapshot at authenticated CONTROL readiness: applies after ICCOA reconnect.
    // OFF by default; no periodic work for the original event-driven mode.
    private var navigationRefreshEnabled = false
    // OEM SensorMgr is enabled only while Android Auto is connected or real navigation runs.
    // This is an experimentally verifiable activation hint, never a fabricated GPS fix.
    private var aaSensorAssist = false
    // OEM GPS is also needed for FT's own coordinate/course widgets.
    // Only enabled while an actual ICCOA video projection is established.
    private var ftProjectionSensorAssist = false
    private val navigationSend: Runnable = object : Runnable {
        override fun run() {
            val connection = session ?: return
            val activation = IccoaNavigation.sensorActivation(latestNavigation, aaSensorAssist, ftProjectionSensorAssist)
            // OEM Control11 is sensor activation; the arrow-to-meter route is unproven.
            val encoded = IccoaNavigation.encode(activation)
            val now = android.os.SystemClock.elapsedRealtime()
            // Most HUs only need state changes; a compatibility resend is opt-in
            // and only allowed for active sensor requests.
            if (!navigationGate.shouldSend(encoded, now,
                    navigationRefreshEnabled && activation.active)) return
            if (connection.sendControl(IccoaNavigation.CONTROL_METHOD, encoded)) {
                navigationGate.accepted(encoded, now)
                navigationRetries = 0
                main.removeCallbacks(navigationRefresh)
                if (navigationRefreshEnabled && activation.active) {
                    main.postDelayed(navigationRefresh, IccoaControl11Gate.REFRESH_MS)
                }
                DiagLog.i("ICCOA", "OEM sensor activation request: active=${activation.active} " +
                    "guided=${latestNavigation.active} aaConnected=$aaSensorAssist " +
                    "ftProjected=$ftProjectionSensorAssist")
            } else if (navigationRetries++ < 8) {
                main.postDelayed(this, 500)
            }
        }
    }

    // Exactly one delayed callback after each successfully queued ACTIVE Control11.
    // Unlike 5-second polling this does no work at all when disabled or inactive.
    private val navigationRefresh: Runnable = object : Runnable {
        override fun run() {
            if (session == null || !navigationRefreshEnabled) return
            val active = IccoaNavigation.sensorActivation(
                latestNavigation, aaSensorAssist, ftProjectionSensorAssist).active
            if (active) navigationSend.run()
        }
    }

    private val microphoneRetry = object : Runnable {
        override fun run() {
            if (microphone?.flush() == true) main.postDelayed(this, 500)
        }
    }
    private var projecting = false
    private var foregroundMode: Boolean? = null
    private var lastVideoConfig: IccoaVideoConfig? = null
    private val audioPlayerState = IccoaAudioPlayerState()
    private val audioStateRetry = Runnable { flushAudioState() }
    private val sensorLiveness = IccoaSensorSessionLiveness()
    private var lastCarGps: IccoaGpsFix? = null
    private fun onSensorMessage(method: Int, body: ByteArray) {
        if (method == IccoaInertialWire.GYRO_METHOD || method == IccoaInertialWire.ACCEL_METHOD) {
            val gyro = method == IccoaInertialWire.GYRO_METHOD
            val decoded = if (gyro) IccoaInertialWire.decodeGyro(body)
                else IccoaInertialWire.decodeAcceleration(body)
            val kind = if (gyro) MotionKind.GYROSCOPE else MotionKind.ACCELERATION
            if (decoded != null) {
                if (gyro) DrPathEvidence.rawGyro() else DrPathEvidence.rawAcceleration()
            }
            val counter = VehicleMotionTelemetry.accept(
                MotionSource.ICCOA, kind,
                decoded?.let {
                    MotionVector(it.x, it.y, it.z, it.deviceTimestampRaw, it.gyroType)
                }
            )
            // Capture OEM double XYZ as delivered; no additional sensor requests.
            if (decoded != null && FTApp.instance.prefs.vehicleCoordinateLogging) {
                VehicleTraceRecorder.imu(
                    System.currentTimeMillis(), android.os.SystemClock.elapsedRealtimeNanos(),
                    gyro, decoded.deviceTimestampRaw, decoded.x, decoded.y, decoded.z,
                    decoded.gyroType
                )
            }
            val total = counter.accepted + counter.rejected
            if (total <= 3 || (total and (total - 1L)) == 0L) {
                // The diagnostic log records counts, not per-frame raw readings.
                DiagLog.i("ICCOA", "SENSOR ${if (gyro) "GyroScope" else "Acceleration"} " +
                    "received=$total valid=${counter.accepted} invalid=${counter.rejected} " +
                    "payloadBytes=${body.size} units=UNVERIFIED axes=OEM_RAW")
            }
            return
        }
        if (method == IccoaGearWire.SENSOR_METHOD) {
            val acceptedGear = IccoaGearWire.accept(body)
            sensorLiveness.onGear(acceptedGear)
            if (acceptedGear) {
                DrPathEvidence.rawGear()
                if (FTApp.instance.prefs.vehicleCoordinateLogging) {
                    val current = VehicleTelemetry.current.value
                    VehicleTraceRecorder.gear(
                        System.currentTimeMillis(), android.os.SystemClock.elapsedRealtimeNanos(),
                        current?.gearRaw, current?.gearSpeedRaw
                    )
                }
            }
            val gearFrames = sensorLiveness.gearFrames
            if (gearFrames <= 3L || gearFrames and (gearFrames - 1L) == 0L) {
                DiagLog.i("ICCOA", "SENSOR GearInfo count=$gearFrames valid=${sensorLiveness.acceptedGearFrames} " +
                    "payloadBytes=${body.size} unit=unconfirmed " +
                    "rawGear=${VehicleTelemetry.current.value?.gearRaw ?: "NA"} " +
                    "rawSpeed=${VehicleTelemetry.current.value?.gearSpeedRaw ?: "NA"}")
            }
            return
        }
        if (method != IccoaGpsWire.GPS_METHOD) return
        val fix = IccoaGpsWire.decode(body)
        val receivedAtMs = System.currentTimeMillis()
        // Each valid newly received ICCOA GPS frame is an event. Carlink's
        // own timestamp marks serialization, not GNSS acquisition time.
        val frameReceivedAtMs = if (fix != null)
            IccoaGpsTimestampGate.observedAtReceiveMs(receivedAtMs) else null
        sensorLiveness.onGps(frameReceivedAtMs != null)
        if (fix != null) {
            lastCarGps = fix
            // Original raw device timestamp retained in deviceTimestampRaw.
            if (frameReceivedAtMs != null) {
                DrPathEvidence.rawGps(receivedAtMs)
                VehicleTelemetry.publish(VehicleLocation(
                source = "ICCOA", receivedAtMs = receivedAtMs,
                positionAtMs = frameReceivedAtMs,
                latitude = fix.latitude, longitude = fix.longitude,
                // OEM CarLinkSensorMgr reads Android Location.getLatitude/Longitude.
                // WGS84 is the Android API contract; confirm actual HU values on-road.
                coordinatesUnit = "ANDROID_LOCATION_WGS84",
                speedRaw = fix.speedRaw.takeIf { fix.speedPresent },
                headingRaw = fix.headingRaw.takeIf { fix.headingPresent },
                speedKmh = fix.speedRaw.takeIf { fix.speedPresent }?.let(IccoaGpsUnits::speedKmh),
                headingDegrees = fix.headingRaw.takeIf { fix.headingPresent }
                    ?.let(IccoaGpsUnits::headingDegrees),
                speedUnit = "ANDROID_LOCATION_MPS_FLOORED",
                headingUnit = "ANDROID_LOCATION_DEGREES_FLOORED",
                satellitesUsed = fix.satellitesUsed.takeIf { fix.satellitesUsedPresent },
                satellitesVisible = fix.satellitesVisible.takeIf { fix.satellitesVisiblePresent },
                altitudeMeters = fix.altitude.takeIf { fix.altitudePresent },
                altitudeUnit = if (fix.altitudePresent) "ANDROID_LOCATION_METRES" else "UNKNOWN",
                antennaStateRaw = fix.antennaState.takeIf { fix.antennaStatePresent },
                pdopRaw = fix.pdop.takeIf { fix.pdopPresent },
                deviceTimestampRaw = fix.timestampRaw.takeIf { fix.timestampPresent }
                ))
            } else DiagLog.w("ICCOA", "OEM GPS rejected: invalid local receipt clock; " +
                "received=$receivedAtMs rawTs=${fix.timestampRaw} (coordinates omitted)")
            if (FTApp.instance.prefs.vehicleCoordinateLogging) {
                VehicleTraceRecorder.gps(
                    System.currentTimeMillis(), android.os.SystemClock.elapsedRealtimeNanos(),
                    fix.timestampRaw, fix.latitude, fix.longitude, fix.speedRaw, fix.headingRaw,
                    fix.satellitesUsed, fix.satellitesVisible,
                    altitudeRaw = fix.altitude.takeIf { fix.altitudePresent }
                )
            }
        }
        val gpsFrames = sensorLiveness.gpsFrames
        if (gpsFrames <= 3L || gpsFrames and (gpsFrames - 1L) == 0L) {
            DiagLog.i("ICCOA", "SENSOR GPS received=$gpsFrames accepted=${sensorLiveness.acceptedGpsFrames} " +
                "payloadBytes=${body.size} fixPresent=${fix != null} " +
                "satellitesUsed=${fix?.satellitesUsed ?: -1} " +
                "satellitesVisible=${fix?.satellitesVisible ?: -1}")
        }
    }

    private var configScheduled = false
    private val requestConfig = object : Runnable {
        override fun run() {
            if (session?.sendControl(24, byteArrayOf()) == true) {
                DiagLog.i("ICCOA", "Car config request queued")
            } else if (session != null) main.postDelayed(this, 500)
        }
    }
    private val connectionTimeout = Runnable {
        if (_state.value.connecting) connectionFailed("Connection timed out. Open ICCOA on the car and try again.")
    }

    private var bluetoothCalls: app.ft.core.BluetoothCalls? = null
    private var bluetoothReconnect: app.ft.core.CarBluetoothReconnect? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ICCOA Carlink", NotificationManager.IMPORTANCE_LOW)
        )
        discovery = IccoaDiscovery(this) { result ->
            _state.update { it.copy(discovery = result) }
            maybeAutoConnect(result)
            // Every completed scan can be followed by another low-power scan.
            // This waiting loop is independent from the three-attempt auth/transport budget.
            if (result.phase == IccoaPhase.SCAN_COMPLETE ||
                result.phase == IccoaPhase.FAILED ||
                result.phase == IccoaPhase.BLUETOOTH_OFF) {
                // When BT is off there is no radio work; keep checking at low
                // frequency, and resume discovery when the owner enables BT.
                schedulePassiveSeek()
            }
        }
        projection = IccoaProjection(this, ::promoteProjection, {
            session?.sendControl(5, byteArrayOf())
        }, { pcm -> media?.audio(pcm) }, onFailure = { reason -> connectionFailed(reason) },
            onAaConnectionChanged = { connected ->
                main.post {
                    if (!connected) directVideo?.disconnected()
                    if (aaSensorAssist != connected) {
                        aaSensorAssist = connected
                        navigationRetries = 0
                        main.removeCallbacks(navigationSend)
                        DiagLog.i("ICCOA", "AA projection sensor assist changed: connected=$connected")
                        navigationSend.run()
                    }
                }
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = FTApp.instance.prefs
        if (intent?.action == STOP || prefs.carProtocol != CarProtocol.ICCOA || !prefs.autoConnect) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasBluetoothPermission()) {
            _state.value = IccoaState(discovery = IccoaDiscoveryState(phase = IccoaPhase.PERMISSION_REQUIRED))
            stopSelf()
            return START_NOT_STICKY
        }
        try { foreground(projecting) } catch (e: SecurityException) {
            _state.value = IccoaState(status = "Android permission required: open FT and allow Nearby devices.",
                discovery = IccoaDiscoveryState(phase = IccoaPhase.PERMISSION_REQUIRED))
            stopSelf()
            return START_NOT_STICKY
        }
        if (bluetoothCalls == null) {
            bluetoothCalls = app.ft.core.BluetoothCalls(this).also { it.start() }
        }
        if (bluetoothReconnect == null) {
            bluetoothReconnect = app.ft.core.CarBluetoothReconnect(this, prefs).also { it.start() }
        }
        val wasRunning = _state.value.running
        _state.update { it.copy(running = true) }
        when (intent?.action) {
            CONNECT -> intent.getStringExtra("address")?.let { address ->
                main.removeCallbacks(recoveryScan)
                dropRecovery.onExplicitOwnerRequest()
                connectTo(address, ownerSelected = true)
            }
            CONNECT_NORMAL -> intent.getStringExtra("address")?.let { address ->
                main.removeCallbacks(recoveryScan)
                dropRecovery.onExplicitOwnerRequest()
                connectTo(address, forceCurrentPin = true, ownerSelected = true)
            }
            PIN -> intent.getStringExtra("pin")?.let { pin ->
                if (_state.value.pinRequired && pin.matches(Regex("[0-9]{6}"))) {
                    _state.update { it.copy(pinRequired = false, status = "Confirming the code with the car…") }
                    session?.submitPin(pin)
                }
            }
            DISCONNECT -> {
                // Manual disconnect must agree with the single owner toggle: OFF really stops.
                prefs.autoConnect = false
                main.removeCallbacks(recoveryScan)
                main.removeCallbacks(passiveSeek)
                dropRecovery.onExplicitOwnerRequest()
                endConnection()
                _state.update { it.copy(status = "Disconnected") }
                stopSelf()
            }
            INSPECT -> if (!_state.value.connecting && !_state.value.connected) intent.getStringExtra("address")?.let { discovery?.inspect(it) }
            SCAN -> { endConnection(); autoConnectAttempted = false
                main.removeCallbacks(recoveryScan)
                main.removeCallbacks(passiveSeek)
                seekPolicy.reset()
                dropRecovery.onExplicitOwnerRequest()
                _state.update { it.copy(status = "") }; discovery?.start() }
            else -> if (!wasRunning) startBackgroundScan()
        }
        return START_STICKY
    }

    private fun maybeAutoConnect(result: IccoaDiscoveryState) {
        val prefs = FTApp.instance.prefs
        if (!prefs.autoConnect || prefs.carProtocol != CarProtocol.ICCOA ||
            autoConnectAttempted || _state.value.connecting || _state.value.connected) return
        // A BLE scan error is not a usable remembered-peer advertisement.
        // During a confirmed recovery episode it must consume a bounded
        // transport retry instead of getting stuck forever on FAILED.
        if (result.phase == IccoaPhase.FAILED) {
            if (dropRecovery.active) {
                val next = dropRecovery.onFailure(false, true, true)
                if (next != null) scheduleRecoveryScan(next)
                else DiagLog.w("ICCOA", "BLE discovery failed; recovery attempts exhausted")
            }
            return
        }
        // Missing permissions, disabled Bluetooth and pending inspection are
        // never permission to retry or connect to a stale candidate.
        if (result.phase != IccoaPhase.SCANNING &&
            result.phase != IccoaPhase.SCAN_COMPLETE) return
        val car = rememberedCar.select(result.peers, result.phase == IccoaPhase.SCAN_COMPLETE)
        if (car == null) {
            // A BLE address may rotate or the car may temporarily disappear.
            // A completed discovery with no remembered candidate consumes the
            // next recovery slot, but never authorizes PIN/auth retry.
            if (dropRecovery.active && result.phase == IccoaPhase.SCAN_COMPLETE) {
                val next = dropRecovery.onFailure(false, true, true)
                if (next != null) scheduleRecoveryScan(next)
                else DiagLog.w("ICCOA", "Remembered car not found after three recovery attempts")
            }
            return
        }
        autoConnectAttempted = true
        main.removeCallbacks(passiveSeek)
        val attempt = dropRecovery.onInitialRememberedCarAutoSelect()
        DiagLog.i("ICCOA", "Auto-select remembered vehicle, attempt $attempt/" +
            "${IccoaDropRecovery.MAX_ATTEMPTS}; encrypted credential tried once per session if available")
        connectTo(car.address)
    }

    private fun connectTo(address: String, forceCurrentPin: Boolean = false,
                          ownerSelected: Boolean = false) {
        pendingCar = _state.value.discovery.peers.singleOrNull { it.address.equals(address, ignoreCase = true) }
        val known = rememberedCar.matches(pendingCar, _state.value.discovery.peers)
        // ServiceData is an untrusted lookup hint. Pinned OEM EC signatures,
        // not BLE names, tokens or rotating addresses, authenticate QUICK.
        // Preserve the existing PRIMARY for receivers without a stable token.
        val tokenScope = IccoaQuickTrustPolicy.scopeForReceiverToken(pendingCar?.receiverToken)
        val trustedCarName = tokenScope
            ?: if (known) IccoaQuickTrustPolicy.PRIMARY_TRUST_SCOPE else ""
        val legacyTrust = if (known && tokenScope != null)
            IccoaQuickTrustPolicy.PRIMARY_TRUST_SCOPE else ""
        val canTryQuick = IccoaQuickTrustPolicy.mayAttemptQuick(known, ownerSelected, forceCurrentPin)
        DiagLog.i("ICCOA", "Quick eligibility remembered=$known manuallySelected=$ownerSelected forcedNormal=$forceCurrentPin eligible=$canTryQuick")
        endConnection()
        DrPathEvidence.beginSession()
        // A stopped in-progress scan publishes SCAN_COMPLETE. Mark ourselves
        // as connecting BEFORE stopping it: otherwise that callback can
        // auto-select the remembered car in the middle of a manual Connect.
        _state.update { it.copy(connecting = true, status = "Connecting to the car…") }
        discovery?.stop()
        val token = generation
        main.postDelayed(connectionTimeout, 180_000)
        fun current(action: () -> Unit) { main.post { if (generation == token) action() } }
        lateinit var connection: IccoaSession
        val carMicrophone = IccoaMicrophone(
            sendControl = { method, body -> connection.sendControl(method, body) },
            onPcm = { pcm -> if (generation == token) AaHeadUnitService.feedCarMicrophone(pcm) },
            log = { DiagLog.i("ICCOA", it) },
            phoneStateBody = ::phoneState,
            gain = { FTApp.instance.prefs.iccoaMicGain })
        microphone = carMicrophone
        val hook: (Boolean) -> Unit = { wanted ->
            carMicrophone.setWanted(wanted)
            main.post {
                if (generation == token) {
                    main.removeCallbacks(microphoneRetry)
                    microphoneRetry.run()
                }
            }
        }
        val navHook: (NavigationInfo) -> Unit = { info ->
            main.post {
                if (generation == token) {
                    latestNavigation = info
                    navigationRetries = 0
                    main.removeCallbacks(navigationSend)
                    navigationSend.run()
                }
            }
        }
        connection = IccoaSession(this,
            onStatus = { text -> current { _state.update { it.copy(status = text) } } },
            onPinRequired = { current {
                dropRecovery.onPinRequired()
                main.removeCallbacks(recoveryScan)
                _state.update { it.copy(pinRequired = true, status = "Enter the six-digit code shown by the car") }
            } },
            onReady = { network, peer ->
                // AuthConfirm must only advertise our address after these listeners are bound.
                synchronized(lifecycle) {
                    check(generation == token) { "Connection cancelled" }
                    val transport = IccoaMediaTransport(network, peer,
                        decryptInput = { session?.decryptInput(it) ?: error("Session ended") },
                        onPlay = { config -> current { play(config) } },
                        onPause = { current { pauseProjection() } },
                        onKeyframe = { current {
                            if (directVideo?.requestKeyframe() != true) projection?.requestKeyFrame()
                        } },
                        onTouch = { touch -> current { projection?.touch(touch) } },
                        onKey = { code -> current { projection?.key(code) } },
                        onClosed = { reason -> current {
                            connectionFailed(reason, retryableTransportFailure = true)
                        } })
                    transport.start()
                    connectionVideoMode = FTApp.instance.prefs.iccoaVideoMode
                    media = transport
                }
                current {
                    _state.update { it.copy(pinRequired = false, status = "Paired. Waiting for the car to start projection…") }
                    navigationRetries = 0
                    navigationSend.run()
                }
            },
            onMessage = { category, method, body ->
                if (category == 4) {
                    if (generation == token) carMicrophone.receive(method, body)
                } else current {
                    if (category == IccoaGpsWire.SENSOR_CATEGORY) onSensorMessage(method, body)
                    else control(category, method, body)
                }
            },
            onClosed = { event -> current {
                if (event.action == IccoaCloseAction.REQUIRE_NORMAL_PIN) {
                    val selected = pendingCar?.address
                    DiagLog.i("ICCOA", "OEM rejected QUICK; switching to NORMAL and asking for current PIN")
                    endConnection()
                    if (selected == null) connectionFailed("QUICK: select the car and enter its PIN")
                    else connectTo(selected, forceCurrentPin = true)
                } else connectionFailed(event.message, event.action == IccoaCloseAction.RETRY_TRANSPORT)
            } },
            onVerifiedControlReady = { current {
                // The OEM crypto response, AuthConfirm and authenticated CONTROL must all be established.
                // Never send the Classic BT identity on BLE bootstrap or unverified sessions.
                if (session === connection && lastBluetoothLinkSession != token) {
                    val receiverId = connection.verifiedReceiverId
                    if (receiverId == null) {
                        DiagLog.w("ICCOA", "No verified OEM receiver fingerprint at CONTROL; cannot apply per-car settings")
                        return@current
                    }
                    connectionVideoMode = FTApp.instance.prefs.iccoaVideoModeFor(receiverId)
                    IccoaCalibrationScope.activate(receiverId, connection.verifiedLegacyPrimary)
                    _state.update { it.copy(verifiedReceiverId = receiverId) }
                    lastBluetoothLinkSession = token
                    val payload = bluetoothIdentityLink.addPayload(receiverId,
                        connection.verifiedLegacyPrimary)
                    if (payload != null) {
                        // True means queued for this authenticated CONTROL lane, NOT an OEM mapping ACK.
                        val queued = connection.sendControl(IccoaBluetoothMacInfo.CONTROL_METHOD, payload)
                        DiagLog.i("ICCOA", "BT identity auto-association method22 queued=$queued; OEM mapping/HFP status UNKNOWN")
                    } else {
                        // No synthetic MAC, no repeated permission prompt and no unverified OEM extension.
                        DiagLog.i("ICCOA", "BT identity auto-association skipped: phone Classic MAC unknown or OEM receiver unsupported")
                    }
                }
            } },
            onControlReady = { current {
                navigationGate.reset()
                navigationRetries = 0
                main.removeCallbacks(navigationSend)
                main.removeCallbacks(navigationRefresh)
                navigationRefreshEnabled = FTApp.instance.prefs.iccoaSensorRefresh
                DiagLog.i("ICCOA", "OEM sensor refresh compatibility enabled=$navigationRefreshEnabled")
                navigationSend.run()
            } },
            trustedCarName = trustedCarName,
            legacyTrustedCarName = legacyTrust,
            tryRememberedPin = canTryQuick,
            forceCurrentPin = forceCurrentPin)
        session = connection
        main.postDelayed({
            if (generation == token && session === connection && sensorLiveness.gearWithoutAcceptedGps())
                DiagLog.w("ICCOA", "Sensor lane alive (GearInfo=${sensorLiveness.gearFrames}), but zero fresh OEM GPS locations in THIS session;  " +
                    "real guided navigation may be required to activate the car's GPS producer")
        }, 20_000L)
        // Publish only after the captured session has been initialized: AA callbacks
        // arrive on its reader thread and can run as soon as the hook is visible.
        microphoneHook = hook
        navigationHook = navHook
        AaHeadUnitService.onCarMicWanted = hook
        AaHeadUnitService.onNavigation = navHook
        runCatching { connection.start(address) }.onFailure { connectionFailed(it.message ?: "Connection failed") }
    }

    private fun play(config: IccoaVideoConfig) {
        lastVideoConfig = config
        val plan = IccoaVideoPlan.from(config)
        val output = media ?: return
        // Read once for this connection. Preference changes apply on the next
        // car reconnect, never in the middle of an agreed RTSP/WFD session.
        directVideo?.videoPlanChanged(plan)
        if (directVideo == null && connectionVideoMode == 1) {
            val prefs = FTApp.instance.prefs
            val (aaWidth, aaHeight) = if (prefs.aaMatchCar)
                IccoaAaSize.fit(plan.contentWidth, plan.contentHeight)
            else prefs.aaWidth to prefs.aaHeight
            projection?.let { target ->
                directVideo = IccoaDirectVideo(main, output, target, plan, aaWidth, aaHeight,
                    onStatus = { status ->
                        main.post { if (media === output) _state.update { it.copy(status = status) } }
                    },
                    onModeChanged = { isDirect, reason ->
                        main.post { if (media === output) _state.update { it.copy(
                            effectiveVideoMode = if (isDirect) 1 else 0,
                            videoFallbackReason = reason
                        ) } }
                    }
                ).also { app.ft.aa.AaH264Tap.listener = it }
            }
        }
        DiagLog.i("ICCOA", "video profile ${plan.streamWidth}x${plan.streamHeight}@${plan.fps} CBR ${plan.bitrate / 1000} kbps; receiver asked ${config.fps} fps")
        if (directVideo?.active != true) projection?.start(plan, output::configure, output::frame)
        if (media !== output) return // A synchronous projection failure has already ended this connection.
        if (!ftProjectionSensorAssist) {
            ftProjectionSensorAssist = true
            navigationRetries = 0
            main.removeCallbacks(navigationSend)
            DiagLog.i("ICCOA", "FT projection ready; requesting OEM GPS for coordinate widgets")
            navigationSend.run()
        }
        main.removeCallbacks(connectionTimeout)
        output.resume()
        audioPlayerState.play()
        flushAudioState()
        if (!configScheduled) {
            configScheduled = true
            main.postDelayed(requestConfig, 3_000)
        }
        pendingCar?.let { car ->
            if (rememberedCar.remember(car)) DiagLog.i("ICCOA", "Authenticated vehicle selection remembered for next connection")
        }
        dropRecovery.onConnected()
        seekPolicy.reset()
        main.removeCallbacks(passiveSeek)
        main.removeCallbacks(recoveryScan)
        _state.update { it.copy(connecting = false, connected = true, pinRequired = false,
            status = "ICCOA linked · awaiting H.264 video · ${plan.streamWidth}×${plan.streamHeight} · ${plan.fps} fps") }
    }

    /** Reference 7.62 phone state: no experimental call-state field is advertised. */
    private fun phoneState(voice: Boolean): ByteArray {
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val battery = getSystemService(BatteryManager::class.java)
        val capacity = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging == true
        val thermal = getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
        return ProtoWriter().bool(2, locked).bool(4, voice)
            .bool(5, capacity in 0..15 && !charging)
            .bool(6, thermal >= PowerManager.THERMAL_STATUS_SEVERE).toByteArray()
    }

    private fun control(category: Int, method: Int, body: ByteArray) {
        if (category != 1) return
        when (method) {
            IccoaPortNegotiation.REQUEST -> {
                IccoaPortNegotiation.responseFor(body)?.let { response ->
                    if (session?.sendControl(IccoaPortNegotiation.RESPONSE, response) == true)
                        DiagLog.i("ICCOA", "OEM SENSOR GetPortRequest answered with bound listener 57239")
                }
            }
            4 -> lastVideoConfig?.let(::play)
            5, 41 -> pauseProjection()
            16 -> projection?.key(84) // Same AA voice/search key as the CarLife voice button.
            23 -> connectionFailed("The car disconnected", retryableTransportFailure = true)
            25 -> runCatching { IccoaCarConfig.describe(body) }
                .onSuccess { DiagLog.i("ICCOA", "Car config: $it") }
                .onFailure { DiagLog.w("ICCOA", "Car config unreadable: bytes=${body.size} ${it.javaClass.simpleName}") }
            26 -> session?.sendControl(27, byteArrayOf())
        }
    }

    private fun pauseProjection() {
        // The car can hide its projection surface without ending capture or Android Auto.
        directVideo?.paused()
        media?.pause()
        audioPlayerState.pause()
        flushAudioState()
        _state.update { it.copy(status = "Car paused projection") }
    }

    private fun flushAudioState() {
        main.removeCallbacks(audioStateRetry)
        audioPlayerState.flush { session?.sendControl(8, it) == true }?.let {
            DiagLog.i("ICCOA", "AAC player state $it queued (48 kHz stereo)")
        }
        if (audioPlayerState.pending && session != null) main.postDelayed(audioStateRetry, 500)
    }

    private fun connectionFailed(reason: String, retryableTransportFailure: Boolean = false) {
        DiagLog.w("ICCOA", reason)
        // Three bounded reconnect attempts only for an established session's
        // unexpected transport loss, or transport failure in its recovery chain.
        val number = dropRecovery.onFailure(
            _state.value.connected, FTApp.instance.prefs.autoConnect,
            retryableTransportFailure
        )
        main.removeCallbacks(recoveryScan)
        endConnection()
        if (number != null) {
            autoConnectAttempted = false
            _state.update { it.copy(status =
                "$reason. Переподключение $number/${IccoaDropRecovery.MAX_ATTEMPTS}") }
            scheduleRecoveryScan(number)
        } else {
            // Transport retries have a finite per-episode budget, but ON must
            // never permanently disable searching. Auth/PIN failures do not
            // reset autoConnectAttempted and cannot loop credentials.
            val stillSeeking = seekingAllowed() && retryableTransportFailure
            if (stillSeeking) {
                autoConnectAttempted = false
                _state.update { it.copy(status = "$reason. Ищем автомобиль…") }
                schedulePassiveSeek()
            } else {
                _state.update { it.copy(status = "$reason. Автоматическая авторизация остановлена") }
            }
        }
    }

    private fun endConnection() {
        DrPathEvidence.endSession()
        IccoaCalibrationScope.clear()
        CarTouchCalibration.cancel()
        MirrorTouchCalibration.cancel()
        directVideo?.close()
        directVideo = null
        connectionVideoMode = 0
        _state.update { it.copy(effectiveVideoMode = 0, videoFallbackReason = null,
            verifiedReceiverId = null) }
        main.removeCallbacks(connectionTimeout)
        main.removeCallbacks(audioStateRetry)
        main.removeCallbacks(requestConfig)
        main.removeCallbacks(microphoneRetry)
        main.removeCallbacks(navigationSend)
        main.removeCallbacks(navigationRefresh)
        navigationRefreshEnabled = false
        navigationGate.reset()
        if (AaHeadUnitService.onNavigation === navigationHook) AaHeadUnitService.onNavigation = null
        navigationHook = null
        latestNavigation = NavigationInfo()
        aaSensorAssist = false
        ftProjectionSensorAssist = false
        navigationRetries = 0
        if (AaHeadUnitService.onCarMicWanted === microphoneHook) AaHeadUnitService.onCarMicWanted = null
        microphoneHook = null
        microphone?.close()
        microphone = null
        audioPlayerState.reset()
        configScheduled = false
        synchronized(lifecycle) {
            generation++
            media?.close(); media = null
            session?.stop(); session = null
        }
        projection?.stop()
        lastVideoConfig = null
        lastCarGps = null
        // A reconnect starts a fresh sensor evidence epoch. Counts from the
        // previous car session must not suppress missing-OEM-GPS diagnostics.
        sensorLiveness.reset()
        VehicleTelemetry.clear("ICCOA")
        VehicleMotionTelemetry.clear(MotionSource.ICCOA)
        _state.update { it.copy(pinRequired = false, connecting = false, connected = false) }
    }

    private fun foreground(withProjection: Boolean) {
        if (foregroundMode == withProjection) return
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tile_ft)
            .setContentTitle("FT · ICCOA Carlink").setContentText("ICCOA wireless projection")
            .setContentIntent(launch).setOngoing(true).build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        startForeground(NOTIFICATION, note, type)
        foregroundMode = withProjection
    }

    private fun promoteProjection() { foreground(true); projecting = true }
    private fun hasBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onDestroy() {
        main.removeCallbacks(passiveSeek)
        main.removeCallbacks(recoveryScan)
        dropRecovery.onExplicitOwnerRequest()
        bluetoothReconnect?.close(); bluetoothReconnect = null
        bluetoothCalls?.close(); bluetoothCalls = null
        discovery?.stop(); discovery = null
        endConnection()
        projection?.close(); projection = null
        _state.update { it.copy(running = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundMode = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "iccoa_projection"
        private const val NOTIFICATION = 43
        private const val START = "app.ft.iccoa.START"
        private const val STOP = "app.ft.iccoa.STOP"
        private const val SCAN = "app.ft.iccoa.SCAN"
        private const val INSPECT = "app.ft.iccoa.INSPECT"
        private const val CONNECT = "app.ft.iccoa.CONNECT"
        private const val CONNECT_NORMAL = "app.ft.iccoa.CONNECT_NORMAL"
        private const val PIN = "app.ft.iccoa.PIN"
        private const val DISCONNECT = "app.ft.iccoa.DISCONNECT"
        private val _state = MutableStateFlow(IccoaState())
        val state: StateFlow<IccoaState> = _state
        private var instance: IccoaService? = null
        fun start(context: Context) = send(context, START)
        fun scanAgain(context: Context) = send(context, SCAN)
        fun inspect(context: Context, address: String) = send(context, INSPECT, address = address)
        fun connect(context: Context, address: String) = send(context, CONNECT, address = address)
        fun connectNormal(context: Context, address: String) = send(context, CONNECT_NORMAL, address = address)
        fun submitPin(context: Context, pin: String) = send(context, PIN, pin = pin)
        fun disconnect(context: Context) = send(context, DISCONNECT)
        fun calibrateTouch() { if (_state.value.connected && _state.value.verifiedReceiverId != null) instance?.projection?.startTouchCalibration() }
        fun resetTouchCalibration() { if (_state.value.connected) instance?.projection?.resetTouchCalibration() }
        fun calibrateMirrorTouch() { if (_state.value.connected && _state.value.verifiedReceiverId != null) instance?.projection?.startMirrorTouchCalibration() }
        fun resetMirrorTouchCalibration() { if (_state.value.connected) instance?.projection?.resetMirrorTouchCalibration() }
        /** Safety control usable on the phone even when the HU cannot display direct H.264. */
        fun restoreNormalVideo() {
            instance?.let { service -> service.main.post { service.directVideo?.restoreNormalByUser() } }
        }
        fun stop(context: Context) { if (instance != null) send(context, STOP) }
        private fun send(context: Context, action: String, address: String? = null, pin: String? = null) {
            val intent = Intent(context, IccoaService::class.java).setAction(action)
                .putExtra("address", address).putExtra("pin", pin)
            runCatching {
                if (action == STOP) context.startService(intent) else context.startForegroundService(intent)
            }.onFailure { DiagLog.w("ICCOA", "Android refused $action: ${it.javaClass.simpleName}; open FT and try again") }
        }
    }
}
