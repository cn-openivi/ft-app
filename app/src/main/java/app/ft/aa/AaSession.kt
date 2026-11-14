package app.ft.aa

import android.content.Context
import app.ft.core.Bytes
import app.ft.core.CarAudioBus
import app.ft.core.DiagLog
import app.ft.core.Prefs
import app.ft.core.CarProtocol
import app.ft.core.VehicleTelemetry
import app.ft.dr.DrNavigationTelemetry
import app.ft.dr.FtPositionTelemetry
import app.ft.dr.DrPathEvidence
import app.ft.dr.FtNavigationPosition
import app.ft.core.ProtoReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.io.InputStream
import java.io.OutputStream

private val OFF_MAIN = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ft-aa-main-sends").apply { isDaemon = true } }

class AaSession(
    context: Context,
    private val input: InputStream,
    private val output: OutputStream,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val decoder: AaVideoDecoder,
    developerSelfMode: Boolean,
    private val onClosed: (String) -> Unit
) {
    enum class Phase { CONNECTED, VERSIONED, HANDSHAKING, AUTHENTICATED, DISCOVERED, STREAMING }

    private val tag = "AA"
    // Passive, per-session instrumentation. This cannot change AA GPS policy.
    private val gpsEvidence = AaGpsEvidence.begin(developerSelfMode)
    private val crypto = AaCrypto(context)
    private val authCompletion = AaAuthCompletionGate()
    private val assembler = AaFraming.Assembler { crypto.decrypt(it) }
    private val outLock = Any()
    private var job: Job? = null
    private var carGpsJob: Job? = null
    private var carGearJob: Job? = null
    private var carSatellitesJob: Job? = null
    @Volatile private var carGpsSubscribed = false
    @Volatile private var carGearSubscribed = false
    @Volatile private var carSatellitesSubscribed = false
    private val sensorSubscriptions = AaSensorSubscriptions()
    private var locationCharacterizationAtDiscovery =
        AaLocationCharacterization.RAW_GNSS_ONLY
    private val audioRouteAudit = AaAudioRouteAudit()
    @Volatile private var sensorChannelOpened = false

    /** Channel CLOSE invalidates *both* active stream subscriptions. */
    private fun stopCarSensorStreams() {
        carGpsSubscribed = false
        carGearSubscribed = false
        carSatellitesSubscribed = false
        carGpsJob?.cancel(); carGpsJob = null
        carGearJob?.cancel(); carGearJob = null
        carSatellitesJob?.cancel(); carSatellitesJob = null
    }

    private val lifecycle = AaSessionLifecycle(
        interrupt = {
            runCatching { input.close() }
            runCatching { output.close() }
            job?.cancel()
        },
        cleanup = { reason ->
            stopCarSensorStreams()
            sensorChannelOpened = false
            gpsEvidence.channelClosed()
            gpsEvidence.finish()
            DiagLog.i(tag, "AA vehicle GPS evidence at shutdown: ${gpsEvidence.summary()}")
            DiagLog.i(tag, "AA sensor subscriptions at shutdown: ${sensorSubscriptions.summary()}")
            DiagLog.i(tag, "AA actual audio arrival at shutdown: ${audioRouteAudit.summary()}")
            sensorSubscriptions.reset()
            microphoneInput.close()
            runCatching { mic.stop() }
            if (micWanted) {
                micWanted = false
                runCatching { AaHeadUnitService.onCarMicWanted?.invoke(false) }
            }
            // Channel close and abrupt session termination must release any
            // still-active audio lanes, including speech. Unpaired AV_STOP
            // is legal and must not end another session's current lane.
            audioSessions.stopAll().forEach { channel ->
                runCatching { CarAudioBus.end(channel) }
            }
            audioMeasured.clear()
            runCatching { synchronized(navigation) { AaHeadUnitService.onNavigation?.invoke(navigation.reset()) } }
            synchronized(music) { AaHeadUnitService.publishMusic(music.reset()) }
            try { AaH264Tap.stop(this@AaSession); decoder.reset() } finally { onClosed(reason) }
        }
    )
    private var videoSession = 0
    private val audioSessions = AaAudioChannelRegistry()
    private val audioMeasured = HashSet<Int>()
    private var mediaFrames = 0L
    private val _phase = MutableStateFlow(Phase.CONNECTED)
    val phase: StateFlow<Phase> = _phase
    @Volatile var hasStreamed = false
        private set
    @Volatile var remoteBye = false
        private set
    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName
    private val openChannels = HashSet<Int>()
    private val navigation = AaNavigation()
    private val music = AaMusic()
    private val mic = AaMicrophone { }
    private val microphoneInput = AaMicrophoneInput(::sendMicrophone, { mic.stopAsync() }, { DiagLog.i(tag, it) })
    private var microphoneGeneration = 0L
    @Volatile private var micWanted = false
    @Volatile private var carMicAt = 0L

    fun start() {
        if (!lifecycle.start()) return
        job = scope.launch(Dispatchers.IO) {
            var reason = "reader stopped"
            try {
                sendPlain(AaProtocol.CH_CONTROL, AaProtocol.VERSION_REQUEST, AaMessages.versionRequest(), false)
                DiagLog.i(tag, "version request sent")
                while (isActive && lifecycle.running) {
                    val frame = AaFraming.readFrame(input)
                    lifecycle.dispatch {
                        assembler.feed(frame)?.let { dispatch(it) }
                    }
                }
            } catch (t: Throwable) {
                reason = "read ${t.javaClass.simpleName}: ${t.message}"
                if (lifecycle.running) DiagLog.w(tag, "session ended: $reason")
            } finally {
                close(reason)
            }
        }
    }

    fun close(reason: String) {
        lifecycle.close(reason)
    }

    private fun sendPlain(channel: Int, id: Int, body: ByteArray, control: Boolean) = send(channel, id, body, false, control)
    private fun sendEnc(
        channel: Int, id: Int, body: ByteArray, control: Boolean = false,
        afterWrite: (() -> Unit)? = null
    ) = send(channel, id, body, true, control, afterWrite)

    private fun send(
        channel: Int, id: Int, body: ByteArray, encrypted: Boolean, control: Boolean,
        afterWrite: (() -> Unit)? = null
    ) {
        if (!lifecycle.running) return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            OFF_MAIN.execute { send(channel, id, body, encrypted, control, afterWrite) }
            return
        }
        val payload = ByteArray(2 + body.size)
        Bytes.putU16(id, payload, 0)
        System.arraycopy(body, 0, payload, 2, body.size)
        val failure = synchronized(outLock) {
            if (!lifecycle.running) return
            try {
                val frames = AaFraming.encode(channel, payload, encrypted, control) { if (encrypted) crypto.encrypt(it) else it }
                frames.forEach { output.write(it) }
                output.flush()
                null
            } catch (t: Throwable) {
                t
            }
        }
        // Never wait for dispatch cleanup while holding outLock.
        if (failure != null) {
            if (lifecycle.running) DiagLog.e(tag, "write failed", failure)
            close("write ${failure.javaClass.simpleName}: ${failure.message}")
        } else {
            // Socket flush proves that FT wrote to the transport, never that
            // the Android Auto app consumed or chose these coordinates.
            runCatching { afterWrite?.invoke() }
                .onFailure { DiagLog.w(tag, "post-write GPS audit failed") }
        }
    }

    private fun dispatch(m: AaFraming.Message) {
        val id = m.id
        val body = m.body
        if (m.channel == AaProtocol.CH_CONTROL || m.control) {
            handleControl(m.channel, id, body)
            return
        }
        when (m.channel) {
            AaProtocol.CH_VIDEO -> handleVideo(id, body)
            AaProtocol.CH_INPUT -> handleInput(id, body)
            AaProtocol.CH_SENSOR -> handleSensor(id, body)
            AaProtocol.CH_MEDIA_AUDIO, AaProtocol.CH_SPEECH_AUDIO, AaProtocol.CH_SYSTEM_AUDIO -> handleAudioSink(m.channel, id, body)
            AaProtocol.CH_AV_INPUT -> handleAvInput(id, body)
            AaProtocol.CH_MEDIA_STATUS -> synchronized(music) {
                if (lifecycle.running) music.accept(id, body)?.let { AaHeadUnitService.publishMusic(it) }
            }
            AaProtocol.CH_NAVIGATION -> synchronized(navigation) {
                if (lifecycle.running) {
                    // Route/turn messages carry no speech samples; count separately
                    // to diagnose navigator UI without AA voice PCM.
                    audioRouteAudit.navigationMessage()
                    navigation.accept(id, body)?.let { AaHeadUnitService.onNavigation?.invoke(it) }
                }
            }
            else -> DiagLog.d(tag, "${AaProtocol.channelName(m.channel)} msg 0x${Integer.toHexString(id)} len=${body.size}")
        }
    }

    private fun handleControl(channel: Int, id: Int, body: ByteArray) {
        if (id != AaProtocol.PING_REQUEST && id != AaProtocol.SSL_HANDSHAKE) DiagLog.rx(tag, "${AaProtocol.channelName(channel)} ${AaProtocol.controlName(id)}", body)
        when (id) {
            AaProtocol.VERSION_RESPONSE -> {
                val v = AaMessages.parseVersionResponse(body)
                DiagLog.i(tag, "phone protocol ${v.major}.${v.minor} status=${v.status}")
                _phase.value = Phase.VERSIONED
                val hello = crypto.handshakeStep(null)
                if (hello.isNotEmpty()) sendPlain(AaProtocol.CH_CONTROL, AaProtocol.SSL_HANDSHAKE, hello, false)
                _phase.value = Phase.HANDSHAKING
            }
            AaProtocol.SSL_HANDSHAKE -> {
                val out = crypto.handshakeStep(body)
                if (out.isNotEmpty()) sendPlain(AaProtocol.CH_CONTROL, AaProtocol.SSL_HANDSHAKE, out, false)
                if (authCompletion.shouldSend(crypto.handshakeDone)) {
                    sendPlain(AaProtocol.CH_CONTROL, AaProtocol.AUTH_COMPLETE, AaMessages.authComplete(), false)
                    _phase.value = Phase.AUTHENTICATED
                    DiagLog.i(tag, "TLS handshake complete, auth complete sent")
                }
            }
            AaProtocol.SERVICE_DISCOVERY_REQUEST -> {
                _deviceName.value = AaMessages.deviceName(body)
                DiagLog.i(tag, "service discovery from '${_deviceName.value}'")
                DiagLog.i(tag, "video settings ${prefs.aaWidth}x${prefs.aaHeight}@${prefs.aaFps} fps; " +
                    "Android Auto display density requested=${prefs.aaDensity} " +
                    "effective=${AaUiDensity.effective(prefs.aaDensity, prefs.aaWidth, prefs.aaHeight)} dpi")
                // Session-level declaration; a TinyEKF class in the APK is
                // not proof of actual verified wheel-speed/yaw telemetry.
                val drAtDiscovery = DrNavigationTelemetry.current.value
                val drReadyAtDiscovery = prefs.gpsSourceMode.advertisesCarLocation(
                    prefs.carProtocol) && drAtDiscovery?.eligibleForAa(
                    System.currentTimeMillis()) == true
                locationCharacterizationAtDiscovery =
                    AaLocationCharacterization.forSession(drReadyAtDiscovery)
                val discovery = AaMessages.serviceDiscoveryResponse(
                    prefs.aaWidth, prefs.aaHeight, prefs.aaFps, prefs.aaDensity,
                    true, prefs.carName, prefs.aaControlsLeft,
                    advertiseCarLocation = prefs.gpsSourceMode.advertisesCarLocation(prefs.carProtocol),
                    advertiseCarGear = prefs.carProtocol == CarProtocol.ICCOA,
                    locationCharacterization = locationCharacterizationAtDiscovery,
                    vehicleId = prefs.aaVehicleId,
                    vehicleMake = if (prefs.carProtocol == CarProtocol.ICCOA) "FAW Toyota" else "Universal",
                    vehicleModel = prefs.carName.ifBlank { "Vehicle" },
                    vehicleYear = if (prefs.carProtocol == CarProtocol.ICCOA) "2026" else "2024"
                )
                // Audit actual serialized descriptor, rather than the setting
                // supplied to the encoder. No coordinates or device identity.
                val advertised = ProtoReader(discovery).messages(1)
                    .firstOrNull { it.int(1) == AaProtocol.CH_SENSOR }
                    ?.message(2)?.messages(1)?.map { it.int(1) }.orEmpty()
                val sensorConfig = ProtoReader(discovery).messages(1)
                    .firstOrNull { it.int(1) == AaProtocol.CH_SENSOR }?.message(2)
                val navConfig = ProtoReader(discovery).messages(1)
                    .firstOrNull { it.int(1) == AaProtocol.CH_NAVIGATION }?.message(8)
                DiagLog.i(tag, "AA NAV SDP capability type=${navConfig?.int(2, -1)} " +
                    "intervalMs=${navConfig?.int(1, -1)} " +
                    "imageOptionsPresent=${navConfig?.has(3)}")
                // Log whether the OEM source was genuinely ready during AA
                // capability negotiation, without recording the coordinates.
                // This separates an unrequested sensor from an unavailable fix.
                val oemFixAtDiscovery = VehicleTelemetry.current.value
                val gpsFreshAtDiscovery = oemFixAtDiscovery?.source == "ICCOA" &&
                    oemFixAtDiscovery.coordinatesUnit == "ANDROID_LOCATION_WGS84" &&
                    oemFixAtDiscovery.positionFresh()
                gpsEvidence.discovery(
                    offered = AaProtocol.SENSOR_LOCATION in advertised,
                    characterization = sensorConfig?.int(2) ?: 0,
                    freshFix = gpsFreshAtDiscovery
                )
                DiagLog.i(tag, "AA SENSOR descriptor types=$advertised " +
                    "locationDeclared=${AaProtocol.SENSOR_LOCATION in advertised} " +
                    "locationCharacterization=${sensorConfig?.int(2) ?: 0} " +
                    "characterizationPresent=${sensorConfig?.has(2) == true} " +
                    "oemGpsFreshAtDiscovery=$gpsFreshAtDiscovery")
                sendEnc(AaProtocol.CH_CONTROL, AaProtocol.SERVICE_DISCOVERY_RESPONSE, discovery)
                _phase.value = Phase.DISCOVERED
                if (prefs.gpsSourceMode.advertisesCarLocation(prefs.carProtocol)) {
                    DiagLog.i(tag, "ICCOA car LOCATION selected for AA; waiting for actual phone subscription")
                    scope.launch(Dispatchers.IO) {
                        delay(12_000L)
                        if (lifecycle.running) {
                            DiagLog.i(tag, "AA sensor subscription audit after 12s: " +
                                "sensorChannelOpened=$sensorChannelOpened " +
                                "requests=${sensorSubscriptions.summary()}")
                            if (!sensorChannelOpened) {
                                DiagLog.w(tag, "AA SENSOR channel not opened; no conclusion about LOCATION policy")
                            } else if (!sensorSubscriptions.has(AaProtocol.SENSOR_LOCATION)) {
                                DiagLog.w(tag, "AA SENSOR channel opened but phone did not request LOCATION")
                            }
                            DiagLog.i(tag, "AA GPS evidence: ${gpsEvidence.summary()}")
                            if (!sensorSubscriptions.has(AaProtocol.SENSOR_GEAR))
                                DiagLog.w(tag, "AA phone did not request GEAR; " +
                                    "park override and OEM gear cannot affect apps without subscription")
                        }
                    }
                }
            }
            AaProtocol.CHANNEL_OPEN_REQUEST -> {
                val sid = AaMessages.channelOpenServiceId(body)
                if (channel == AaProtocol.CH_SENSOR) {
                    // A second OPEN starts a fresh subscription lifetime; old
                    // LOCATION/GEAR jobs and request counters cannot carry over.
                    if (sensorChannelOpened) {
                        stopCarSensorStreams()
                        sensorSubscriptions.reset()
                        DiagLog.i(tag, "AA SENSOR reopened; previous subscriptions invalidated")
                    }
                    sensorChannelOpened = true
                    gpsEvidence.channelOpened()
                }
                openChannels += channel
                DiagLog.i(tag, "channel open ${AaProtocol.channelName(channel)} (service $sid)")
                sendEnc(channel, AaProtocol.CHANNEL_OPEN_RESPONSE, AaMessages.channelOpenResponse(), true)
                if (channel == AaProtocol.CH_NAVIGATION) {
                    sendEnc(channel, AaProtocol.NAV_START, ByteArray(0))
                    DiagLog.i(tag, "instrument cluster navigation requested")
                }
            }
            AaProtocol.PING_REQUEST -> sendEnc(AaProtocol.CH_CONTROL, AaProtocol.PING_RESPONSE, AaMessages.pingResponse(AaMessages.pingTimestamp(body)))
            AaProtocol.PING_RESPONSE -> Unit
            AaProtocol.AUDIO_FOCUS_REQUEST -> {
                val req = AaMessages.audioFocusRequestType(body)
                val state = if (req == AaProtocol.AUDIO_REQUEST_RELEASE) AaProtocol.AUDIO_FOCUS_LOSS else AaProtocol.AUDIO_FOCUS_GAIN
                sendEnc(AaProtocol.CH_CONTROL, AaProtocol.AUDIO_FOCUS_NOTIFICATION, AaMessages.audioFocusNotification(state))
            }
            AaProtocol.NAV_FOCUS_REQUEST -> sendEnc(AaProtocol.CH_CONTROL, AaProtocol.NAV_FOCUS_NOTIFICATION, AaMessages.navFocusNotification(AaProtocol.NAV_FOCUS_PROJECTED))
            AaProtocol.BYEBYE_REQUEST -> {
                remoteBye = true
                DiagLog.i(tag, "phone bye-bye reason=${AaMessages.byeByeReason(body)}")
                sendEnc(AaProtocol.CH_CONTROL, AaProtocol.BYEBYE_RESPONSE, AaMessages.byeByeResponse())
                close("bye-bye")
            }
            AaProtocol.CHANNEL_CLOSE_NOTIFICATION -> {
                openChannels -= channel
                if (channel == AaProtocol.CH_SENSOR) {
                    sensorChannelOpened = false
                    gpsEvidence.channelClosed()
                    DiagLog.i(tag, "AA SENSOR channel closed: stopping GPS and gear subscriptions; " +
                        "last requests=${sensorSubscriptions.summary()}")
                    stopCarSensorStreams()
                    sensorSubscriptions.reset()
                }
                if (channel == AaProtocol.CH_MEDIA_AUDIO ||
                    channel == AaProtocol.CH_SPEECH_AUDIO ||
                    channel == AaProtocol.CH_SYSTEM_AUDIO) {
                    if (audioSessions.stop(channel)) CarAudioBus.end(channel)
                    audioMeasured -= channel
                    DiagLog.i(tag, "AA audio channel closed: ${AaProtocol.channelName(channel)}")
                }
                if (channel == AaProtocol.CH_NAVIGATION) synchronized(navigation) {
                    AaHeadUnitService.onNavigation?.invoke(navigation.reset())
                }
                if (channel == AaProtocol.CH_MEDIA_STATUS) synchronized(music) {
                    AaHeadUnitService.publishMusic(music.reset())
                }
                DiagLog.i(tag, "channel close ${AaProtocol.channelName(channel)}")
            }
            AaProtocol.VOICE_SESSION_NOTIFICATION -> Unit
            else -> DiagLog.w(tag, "unhandled control ${AaProtocol.controlName(id)} on ${AaProtocol.channelName(channel)}")
        }
    }

    fun askForAFreshPicture() {
        if (_phase.value != Phase.STREAMING) return
        runCatching {
            sendEnc(AaProtocol.CH_VIDEO, AaProtocol.VIDEO_FOCUS_INDICATION, AaMessages.videoFocusIndication(AaProtocol.VIDEO_FOCUS_PROJECTED, true))
        }.onFailure { DiagLog.w(tag, "could not ask Android Auto for a fresh picture: ${it.message}") }
    }

    private fun handleVideo(id: Int, body: ByteArray) {
        when (id) {
            AaProtocol.AV_SETUP_REQUEST -> {
                DiagLog.i(tag, "video setup config=${AaMessages.setupConfigIndex(body)}")
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.AV_SETUP_RESPONSE, AaMessages.avSetupResponse())
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.VIDEO_FOCUS_INDICATION, AaMessages.videoFocusIndication(AaProtocol.VIDEO_FOCUS_PROJECTED, false))
            }
            AaProtocol.AV_START_INDICATION -> {
                hasStreamed = true
                videoSession = AaMessages.startSession(body)
                mediaFrames = 0
                // Surface changes can request this while holding the decoder lock.
                // Never wait for session cleanup from that callback.
                decoder.askForAFreshPicture = { scope.launch { askForAFreshPicture() } }
                _phase.value = Phase.STREAMING
                AaH264Tap.start(this)
                DiagLog.i(tag, "video start session=$videoSession")
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.VIDEO_FOCUS_INDICATION, AaMessages.videoFocusIndication(AaProtocol.VIDEO_FOCUS_PROJECTED, false))
            }
            AaProtocol.AV_STOP_INDICATION -> {
                DiagLog.i(tag, "video stop")
                AaH264Tap.stop(this)
                decoder.stop()
                _phase.value = Phase.DISCOVERED
            }
            AaProtocol.AV_MEDIA_INDICATION -> {
                DiagLog.i(tag, "video codec config ${body.size} bytes")
                AaH264Tap.config(this, body)
                decoder.onConfig(body)
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.AV_MEDIA_ACK, AaMessages.mediaAck(videoSession))
            }
            AaProtocol.AV_MEDIA_WITH_TIMESTAMP -> {
                if (body.size > 8) {
                    val ts = Bytes.u64(body, 0)
                    mediaFrames++
                    if (mediaFrames <= 3 || mediaFrames % 300 == 0L) DiagLog.d(tag, "video frame #$mediaFrames ${body.size - 8} bytes, ${decoder.frames.value} drawn on the car screen")
                    val frame = body.copyOfRange(8, body.size)
                    if (!AaH264Tap.frame(this, frame, ts)) decoder.onFrame(frame, ts)
                }
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.AV_MEDIA_ACK, AaMessages.mediaAck(videoSession))
            }
            AaProtocol.VIDEO_FOCUS_REQUEST -> {
                val mode = AaMessages.videoFocusRequestMode(body)
                sendEnc(AaProtocol.CH_VIDEO, AaProtocol.VIDEO_FOCUS_INDICATION, AaMessages.videoFocusIndication(mode, false))
            }
            else -> DiagLog.w(tag, "unhandled video ${AaProtocol.avName(id)}")
        }
    }

    private fun handleInput(id: Int, body: ByteArray) {
        when (id) {
            AaProtocol.BINDING_REQUEST -> {
                DiagLog.i(tag, "input binding request")
                sendEnc(AaProtocol.CH_INPUT, AaProtocol.BINDING_RESPONSE, AaMessages.bindingResponse())
            }
            else -> DiagLog.d(tag, "input msg 0x${Integer.toHexString(id)}")
        }
    }

    private fun handleSensor(id: Int, body: ByteArray) {
        if (!sensorChannelOpened || AaProtocol.CH_SENSOR !in openChannels) {
            // Late requests for a closed sensor channel must never restart
            // GPS/GEAR streams after CHANNEL_CLOSE_NOTIFICATION.
            DiagLog.w(tag, "dropping sensor request on unopened/closed channel")
            return
        }
        when (id) {
            AaProtocol.SENSOR_START_REQUEST -> {
                val type = AaMessages.sensorRequestType(body)
                val supported = AaProtocol.supportsSensor(
                    type,
                    isIccoa = prefs.carProtocol == CarProtocol.ICCOA,
                    carLocationOffered = prefs.gpsSourceMode.advertisesCarLocation(prefs.carProtocol)
                )
                if (!supported) {
                    // Reply with a protocol-level rejection instead of OK with no data.
                    // Do not count a rejected request as an active subscription.
                    sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_START_RESPONSE,
                        AaMessages.sensorStartResponse(AaProtocol.STATUS_INVALID_SENSOR))
                    DiagLog.w(tag, "sensor unsupported type=$type; rejected without subscription")
                    return
                }
                sensorSubscriptions.note(type)
                sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_START_RESPONSE,
                    AaMessages.sensorStartResponse())
                when (type) {
                    AaProtocol.SENSOR_DRIVING_STATUS -> sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_EVENT_INDICATION, AaMessages.sensorDrivingStatus(0))
                    AaProtocol.SENSOR_NIGHT_MODE -> sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_EVENT_INDICATION, AaMessages.sensorNightMode(false))
                    AaProtocol.SENSOR_GEAR -> {
                        if (prefs.carProtocol == CarProtocol.ICCOA)
                            startCarGear(ProtoReader(body).long(2, 500L))
                        else sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_ERROR,
                            AaCarGear.sensorError(3))
                    }
                    AaProtocol.SENSOR_LOCATION -> {
                        gpsEvidence.locationRequested()
                        val requestedIntervalMs = ProtoReader(body).long(2, 1_000L)
                        DiagLog.i(tag, "AA phone requested car LOCATION " +
                            "refreshIntervalMs=$requestedIntervalMs")
                        startCarGps(requestedIntervalMs)
                    }
                    AaProtocol.SENSOR_GPS_SATELLITES -> {
                        val requestedIntervalMs = ProtoReader(body).long(2, 1_000L)
                        DiagLog.i(tag, "AA phone requested OEM satellite counts")
                        startCarSatellites(requestedIntervalMs)
                    }
                    else -> Unit
                }
                DiagLog.i(tag, "sensor start type=$type")
            }
            else -> DiagLog.d(tag, "sensor msg 0x${Integer.toHexString(id)}")
        }
    }

    /**
     * Android Auto receives positions FROM FT's authoritative location engine
     * (the same one consumed by native FT widgets/navigation). Frozen SDP
     * RAW_ONLY or FUSED_ONLY determines which packets are legal for this AA
     * session. No direct OEM/DR merge, no duplicate output, no phone fallback.
     */
    private fun startCarGps(requestedMs: Long) {
        carGpsJob?.cancel()
        carGpsSubscribed = true
        val sessionCharacterization = locationCharacterizationAtDiscovery
        val drEvidenceSession = DrPathEvidence.sessionId()
        carGpsJob = scope.launch(Dispatchers.IO) {
            val gate = AaLocationFeedPolicy(
                sessionCharacterization, System.currentTimeMillis())
            // FT alone selects RAW or verified FUSED. Early AA capability
            // negotiation must never starve the already subscribed receiver
            // when FT gains independently verified sensor fusion later.
            FtPositionTelemetry.updates.collect { position ->
                if (!isActive || !lifecycle.running || !carGpsSubscribed ||
                    !sensorChannelOpened) return@collect
                val nowWall = System.currentTimeMillis()
                val packet = when (position.source) {
                    FtNavigationPosition.Source.FUSED ->
                        position.fused?.let { gate.fused(it, nowWall) }
                    FtNavigationPosition.Source.RAW_UNFILTERED ->
                        position.raw?.let { if (!gate.fusedOnly) gate.raw(it, nowWall) else null }
                }
                if (packet != null) {
                    gpsEvidence.eventSubmitted()
                    sendEnc(AaProtocol.CH_SENSOR,
                        AaProtocol.SENSOR_EVENT_INDICATION, packet, afterWrite = {
                            gpsEvidence.socketWriteFlushed()
                            DrPathEvidence.aaLocationWritten(
                                position.source == FtNavigationPosition.Source.FUSED,
                                drEvidenceSession)
                            val written = gpsEvidence.snapshot()?.eventsFlushed ?: 0L
                            if (written == 1L || written % 100L == 0L)
                                DiagLog.i(tag, "AA LOCATION socket flushed count=$written; " +
                                    "source=${position.source} phoneConsumed=UNKNOWN")
                        })
                }
            }
        }
        val modeLabel =
            if (AaLocationCharacterization.permitsEstimatedPosition(sessionCharacterization))
                "FUSED_CAPABILITY" else "RAW_CAPABILITY_AT_DISCOVERY"
        DiagLog.i(tag, "AA subscribed LOCATION intervalMs=$requestedMs; " +
            "source=$modeLabel; one source, no GPS-loss timer")
    }

    /** Satellite counts are equally limited to fresh incoming GPS events. */
    private fun startCarSatellites(requestedMs: Long) {
        carSatellitesJob?.cancel()
        carSatellitesSubscribed = true
        carSatellitesJob = scope.launch(Dispatchers.IO) {
            VehicleTelemetry.gpsUpdates.collect { sample ->
                if (!carSatellitesSubscribed || !lifecycle.running ||
                    !sensorChannelOpened) return@collect
                // Actual OEM event stream: never compare it to previous GPS.
                val packet = AaCarGps.satellitesEvent(sample)
                if (packet != null)
                    sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_EVENT_INDICATION, packet)
            }
        }
        DiagLog.i(tag, "AA subscribed to satellites; requestedIntervalMs=$requestedMs, " +
            "new ICCOA events only")
    }

    /**
     * Normally send truthful OEM gear only; the owner-enabled isolated override
     * can supply PARK to Android Auto regardless of the real transmission.
     * This does not modify vehicle sensors or physical gear state.
     * Phone subscription remains mandatory.
     */
    private fun startCarGear(requestedMs: Long) {
        carGearJob?.cancel()
        carGearSubscribed = true
        val interval = requestedMs.coerceIn(100L, 1_000L)
        carGearJob = scope.launch(Dispatchers.IO) {
            var lastSent: Int? = null
            var lastSentAtMs = 0L
            var unavailable = false
            var delivered = 0L
            while (isActive && lifecycle.running && carGearSubscribed && sensorChannelOpened) {
                val now = System.currentTimeMillis()
                val position = VehicleTelemetry.current.value
                val gear = AaCarGear.aaGear(position, now, forcePark = prefs.aaForcePark)
                if (gear != null) {
                    if (unavailable) {
                        sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_ERROR,
                            AaCarGear.sensorError(1))
                        DiagLog.i(tag, "car GEAR source recovered")
                    }
                    // In force-P mode, refresh once per second even if the
                    // physical selector changes. Other modes remain event-driven.
                    if (gear != lastSent || unavailable ||
                        (prefs.aaForcePark && now - lastSentAtMs >= 1_000L)) {
                        val event = AaCarGear.event(position, now, forcePark = prefs.aaForcePark)
                        if (event != null) {
                            sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_EVENT_INDICATION, event)
                            lastSent = gear
                            lastSentAtMs = now
                            delivered++
                            if (delivered <= 3L || delivered % 100L == 0L)
                                DiagLog.i(tag, "car GEAR reported to subscribed AA client; " +
                                    "count=$delivered overridePark=${prefs.aaForcePark}")
                        }
                    }
                    unavailable = false
                } else if (!unavailable) {
                    unavailable = true
                    sendEnc(AaProtocol.CH_SENSOR, AaProtocol.SENSOR_ERROR,
                        AaCarGear.sensorError(2))
                    DiagLog.w(tag, "car GEAR unavailable/stale; no synthetic PARK value supplied")
                }
                delay(interval)
            }
            // GEAR remains available whenever ICCOA is connected. Switching
            // force-P OFF returns to actual fresh OEM GEAR without renegotiation;
            // if OEM data is unavailable, the loop above reports SENSOR_ERROR.
        }
        DiagLog.i(tag, "AA subscribed to GEAR; overridePark=${prefs.aaForcePark} interval=${interval}ms")
    }

    private fun handleAudioSink(channel: Int, id: Int, body: ByteArray) {
        when (id) {
            AaProtocol.AV_SETUP_REQUEST -> sendEnc(channel, AaProtocol.AV_SETUP_RESPONSE, AaMessages.avSetupResponse())
            AaProtocol.AV_START_INDICATION -> {
                val wasAlreadyActive = !audioSessions.start(channel, AaMessages.startSession(body))
                audioMeasured -= channel
                if (!wasAlreadyActive) CarAudioBus.begin(channel)
                DiagLog.i(tag, "${AaProtocol.channelName(channel)} started")
            }
            AaProtocol.AV_STOP_INDICATION -> {
                if (audioSessions.stop(channel)) CarAudioBus.end(channel)
                audioMeasured -= channel
                DiagLog.i(tag, "${AaProtocol.channelName(channel)} stopped")
            }
            AaProtocol.AV_MEDIA_WITH_TIMESTAMP, AaProtocol.AV_MEDIA_INDICATION -> {
                if (!audioSessions.isActive(channel)) {
                    // In-flight speech/audio after AV_STOP or channel closure must
                    // not resurrect an ended voice lane or duck the next stream.
                    DiagLog.d(tag, "discard late ${AaProtocol.channelName(channel)} audio after stop")
                    return
                }
                val pcm = if (id == AaProtocol.AV_MEDIA_WITH_TIMESTAMP && body.size > 8) body.copyOfRange(8, body.size) else body
                sendEnc(channel, AaProtocol.AV_MEDIA_ACK, AaMessages.mediaAck(audioSessions.session(channel)))
                if (pcm.isNotEmpty()) {
                    audioRouteAudit.pcm(channel, pcm.size)
                    val rate = if (channel == AaProtocol.CH_MEDIA_AUDIO) 48000 else 16000
                    val ch = if (channel == AaProtocol.CH_MEDIA_AUDIO) 2 else 1
                    if (audioMeasured.add(channel)) {
                        DiagLog.i(tag, "${AaProtocol.channelName(channel)} sends ${pcm.size} byte packets, ${pcm.size * 1000 / (rate * ch * 2)} ms each")
                    }
                    CarAudioBus.play(channel, pcm, rate, ch)
                }
            }
            else -> Unit
        }
    }

    private fun startMicrophone() {
        if (mic.active || !microphoneInput.phoneAllowed) return
        if (!AaHeadUnitService.micReady) {
            DiagLog.w(tag, "Android Auto asked to listen, but FT was started in the background so android will not give it the microphone; open FT and start Android Auto from there")
        }
        if (System.nanoTime() - carMicAt < 2_000_000_000L) {
            DiagLog.i(tag, "using the car's microphone for Android Auto")
            return
        }
        val token = microphoneGeneration
        if (!mic.start { pcm -> lifecycle.dispatch { microphoneInput.phone(token, pcm) } })
            DiagLog.w(tag, "Android Auto asked to listen but the microphone is not available")
    }

    fun feedCarMicrophone(pcm: ByteArray) {
        carMicAt = System.nanoTime()
        lifecycle.dispatch { microphoneInput.car(pcm) }
    }

    private fun sendMicrophone(pcm: ByteArray) {
        if (!lifecycle.running) return
        val payload = ByteArray(8 + pcm.size)
        Bytes.putU64(System.nanoTime() / 1000L, payload, 0)
        System.arraycopy(pcm, 0, payload, 8, pcm.size)
        sendEnc(AaProtocol.CH_AV_INPUT, AaProtocol.AV_MEDIA_WITH_TIMESTAMP, payload)
    }

    private fun handleAvInput(id: Int, body: ByteArray) {
        when (id) {
            AaProtocol.AV_SETUP_REQUEST -> sendEnc(AaProtocol.CH_AV_INPUT, AaProtocol.AV_SETUP_RESPONSE, AaMessages.avSetupResponse())
            AaProtocol.AV_INPUT_OPEN_REQUEST -> {
                val wanted = AaMessages.avInputOpenWanted(body)
                sendEnc(AaProtocol.CH_AV_INPUT, AaProtocol.AV_INPUT_OPEN_RESPONSE, AaMessages.avInputOpenResponse(if (wanted) 0 else AaMessages.avInputOpenSession(body)))
                if (wanted != micWanted) DiagLog.i(tag, if (wanted) "Android Auto opened the microphone" else "Android Auto closed the microphone")
                micWanted = wanted
                if (wanted) microphoneGeneration = microphoneInput.open() else microphoneInput.close()
                runCatching { AaHeadUnitService.onCarMicWanted?.invoke(wanted) }
                if (wanted) startMicrophone() else mic.stop()
            }
            AaProtocol.AV_MEDIA_ACK -> Unit
            else -> Unit
        }
    }

    fun sendTouch(x: Int, y: Int, action: Int) {
        if (_phase.value < Phase.DISCOVERED || AaProtocol.CH_INPUT !in openChannels) return
        val aaAction = when (action) {
            0 -> AaProtocol.POINTER_DOWN
            1 -> AaProtocol.POINTER_UP
            2 -> AaProtocol.POINTER_MOVED
            3 -> AaProtocol.POINTER_CANCEL
            else -> return // Do not turn unknown actions into spurious MOVE.
        }
        sendEnc(AaProtocol.CH_INPUT, AaProtocol.INPUT_EVENT_INDICATION, AaMessages.inputTouch(System.nanoTime(), x, y, aaAction))
    }

    /**
     * True means both media-button edges were handed to the AA sender.
     * It does not prove the phone consumed them; socket writes may be asynchronous.
     */
    fun sendKey(keycode: Int): Boolean {
        if (!lifecycle.running || _phase.value < Phase.DISCOVERED || AaProtocol.CH_INPUT !in openChannels) return false
        sendEnc(AaProtocol.CH_INPUT, AaProtocol.INPUT_EVENT_INDICATION, AaMessages.inputKey(System.nanoTime(), keycode, true))
        sendEnc(AaProtocol.CH_INPUT, AaProtocol.INPUT_EVENT_INDICATION, AaMessages.inputKey(System.nanoTime(), keycode, false))
        return true
    }
}
