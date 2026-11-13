package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import android.telecom.TelecomManager
import app.ft.carlife.JoinWatch
import app.ft.projection.VideoPlans
import app.ft.projection.VideoProfile

class Prefs(context: Context) {
    private val appContext = context.applicationContext
    companion object {
        /** Default only; explicitly saved per-user picture DPI is never migrated. */
        const val DEFAULT_CAR_DENSITY_DPI = 240
    }

    private val sp: SharedPreferences = context.getSharedPreferences("ft", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun set(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun set(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun set(key: String, v: String) = sp.edit().putString(key, v).apply()
    private fun flt(key: String, def: Float) = sp.getFloat(key, def)
    private fun set(key: String, v: Float) = sp.edit().putFloat(key, v).apply()
    private fun lng(key: String, def: Long) = sp.getLong(key, def)
    private fun set(key: String, v: Long) = sp.edit().putLong(key, v).apply()

    val all: Map<String, *> get() = sp.all
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(listener)
    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(listener)
    fun put(key: String, value: Any?) {
        val e = sp.edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            is Long -> e.putLong(key, value)
            is Float -> e.putFloat(key, value)
            is String -> e.putString(key, value)
            null -> e.remove(key)
        }
        e.apply()
    }

    var cmdPort: Int get() = int("cmdPort", 7240); set(v) = set("cmdPort", v)
    var videoPort: Int get() = int("videoPort", 8240); set(v) = set("videoPort", v)
    var mediaPort: Int get() = int("mediaPort", 9240); set(v) = set("mediaPort", v)
    var ttsPort: Int get() = int("ttsPort", 9241); set(v) = set("ttsPort", v)
    var vrPort: Int get() = int("vrPort", 9242); set(v) = set("vrPort", v)
    var touchPort: Int get() = int("touchPort", 9340); set(v) = set("touchPort", v)

    var aaPort: Int get() = int("aaPort", 5288); set(v) = set("aaPort", v)
    var aaSelfPort: Int get() = int("aaSelfPort", 5277); set(v) = set("aaSelfPort", v)
    var aaWidth: Int get() = int("aaWidth", 1280); set(v) = set("aaWidth", v)
    var aaHeight: Int get() = int("aaHeight", 720); set(v) = set("aaHeight", v)
    var aaFps: Int get() = app.ft.aa.AaProtocol.supportedFps(int("aaFps", 30)); set(v) = set("aaFps", app.ft.aa.AaProtocol.supportedFps(v))
    // FT167: owner requests ~1.5x larger Android Auto targets.
    // Migrate only the legacy 160 DPI once; preserve intentional custom values.
    // Storing the marker also lets the user manually revert to 160 without
    // subsequent sessions silently overriding that choice.
    var aaDensity: Int
        get() {
            if (!bool("aaDensityBoost167", false)) synchronized(sp) {
                if (!bool("aaDensityBoost167", false)) {
                    val updated = app.ft.aa.AaUiDensity.migrate(int("aaDensity", 160))
                    sp.edit().putInt("aaDensity", updated)
                        .putBoolean("aaDensityBoost167", true).apply()
                }
            }
            return int("aaDensity", 240)
        }
        set(v) = synchronized(sp) {
            sp.edit().putInt("aaDensity", v.coerceIn(120, 480))
                .putBoolean("aaDensityBoost167", true).apply()
        }
    var aaControlsLeft: Boolean get() = bool("aaControlsLeft", true); set(v) = set("aaControlsLeft", v)
    var aaCompactReturnButton: Boolean get() = bool("aaCompactReturnButton", false); set(v) = set("aaCompactReturnButton", v)
    /** AA-only, normalized across head-unit resolutions and rotation. Absence means original selected corner. */
    val aaFloatingReturnPosition: app.ft.ui.car.AaFloatingReturnPlacement.Point?
        get() {
            if (!sp.contains("aaFloatingReturnX") || !sp.contains("aaFloatingReturnY")) return null
            val x = flt("aaFloatingReturnX", Float.NaN)
            val y = flt("aaFloatingReturnY", Float.NaN)
            if (!x.isFinite() || !y.isFinite()) return null
            return app.ft.ui.car.AaFloatingReturnPlacement.Point(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }

    fun saveAaFloatingReturnPosition(position: app.ft.ui.car.AaFloatingReturnPlacement.Point) {
        if (!position.x.isFinite() || !position.y.isFinite()) return
        sp.edit().putFloat("aaFloatingReturnX", position.x.coerceIn(0f, 1f))
            .putFloat("aaFloatingReturnY", position.y.coerceIn(0f, 1f)).apply()
    }
    var aaClusterNavigation: Boolean get() = bool("aaClusterNavigation", true); set(v) = set("aaClusterNavigation", v)
    var aaMatchCar: Boolean get() = bool("aaMatchCar", true); set(v) = set("aaMatchCar", v)
    // Real OEM GEAR is always available in ICCOA; no separate diagnostic toggle.
    // Owner-approved isolated experiment. Off by default and distinct from OEM telemetry.
    var aaForcePark: Boolean get() = bool("aaForcePark", false); set(v) = set("aaForcePark", v)

    var videoSize: Int get() = int("pictureSize", VideoPlans.SIZE_CAR); set(v) = setVideo("pictureSize", v)
    var videoWidth: Int get() = int("videoWidth", 0); set(v) = setVideo("videoWidth", v)
    var videoHeight: Int get() = int("videoHeight", 0); set(v) = setVideo("videoHeight", v)
    var videoFps: Int get() = int("videoFps", 0); set(v) = setVideo("videoFps", v)
    var videoMinFps: Int get() = int("videoMinFps", 0); set(v) = setVideo("videoMinFps", v)
    var videoBitrate: Int get() = int("pictureBitrate", VideoPlans.DEFAULT_BITRATE); set(v) = setVideo("pictureBitrate", v)
    var videoQpFloor: Int get() = int("videoQpFloor", 22); set(v) = setVideo("videoQpFloor", v)
    var carDensity: Int
        get() = int("carDensity", DEFAULT_CAR_DENSITY_DPI)
        set(v) = set("carDensity", v)
    var encoderSavePower: Boolean get() = bool("encoderSavePower", false); set(v) {
        synchronized(videoProfileLock) {
            if (v != encoderSavePower) sp.edit().putBoolean("encoderSavePower", v)
                .remove("savedVideoProfile").putInt("videoProfileGeneration", videoProfileGeneration + 1).apply()
        }
    }
    private fun setVideo(key: String, value: Int) = synchronized(videoProfileLock) {
        if (sp.contains(key) && int(key, 0) == value) return@synchronized
        sp.edit().putInt(key, value).remove("savedVideoProfile")
            .putInt("videoProfileGeneration", videoProfileGeneration + 1).apply()
    }
    val videoSettingsKey: String get() = listOf(videoSize, videoWidth, videoHeight, videoFps,
        videoMinFps, videoBitrate, videoQpFloor, encoderSavePower).joinToString(":")
    val videoProfileGeneration: Int get() = int("videoProfileGeneration", 0)
    val savedVideoProfile: VideoProfile? get() = VideoProfile.decode(str("savedVideoProfile", ""))

    fun saveVideoProfile(profile: VideoProfile, generation: Int) = synchronized(videoProfileLock) {
        if (generation != videoProfileGeneration || profile.settingsKey != videoSettingsKey) return@synchronized
        set("savedVideoProfile", profile.encode())
    }

    fun clearVideoProfile() = synchronized(videoProfileLock) {
        sp.edit().remove("savedVideoProfile")
            .putInt("videoProfileGeneration", videoProfileGeneration + 1).apply()
    }
    var carSizedApps: Boolean get() = bool("carSizedApps", false); set(v) = set("carSizedApps", v)
    var muteWhileProjecting: Boolean get() = bool("muteWhileProjecting", true); set(v) = set("muteWhileProjecting", v)
    var soundOverBluetooth: Boolean get() = bool("soundOverBluetooth", false); set(v) = set("soundOverBluetooth", v)
    var hideChatAudio: Boolean get() = bool("hideChatAudio", true); set(v) = set("hideChatAudio", v)

    var aaBluetooth: Boolean get() = bool("aaBluetooth", false); set(v) = set("aaBluetooth", v)
    var aaPackage: String get() = str("aaPackage", "com.google.android.projection.gearhead"); set(v) = set("aaPackage", v)
    var aaServerPackage: String get() = str("aaServerPackage", ""); set(v) = set("aaServerPackage", v)
    var aaAutoStart: Boolean get() = bool("aaAutoStart", false); set(v) = set("aaAutoStart", v)
    var aaShowReturnButton: Boolean get() = bool("aaShowReturnButton", true); set(v) = set("aaShowReturnButton", v)
    var aaCorner: Int get() = int("aaCorner", 0); set(v) = set("aaCorner", v)
    var carBackground: String get() = str("carBackground", "deep"); set(v) = set("carBackground", v)
    var carAccent: Int get() = int("carAccent", 0xFF5FE3C0.toInt()); set(v) = set("carAccent", v)
    var carTiles: String get() = str("carTiles", "music,videos,youtube,maps,browser,apps"); set(v) = set("carTiles", v)
    var carClock24: Boolean get() = bool("carClock24", true); set(v) = set("carClock24", v)
    var carShowLink: Boolean get() = bool("carShowLink", false); set(v) = set("carShowLink", v)
    var carSongInfo: Boolean get() = bool("carSongInfo", true); set(v) = set("carSongInfo", v)

    var language: String get() = str("language", ""); set(v) = set("language", v)
    var modernPhoneUi: Boolean get() = bool("modernPhoneUi", false); set(v) = set("modernPhoneUi", v)
    var themeMode: Int get() = int("themeMode", 0); set(v) = set("themeMode", v)

    var carName: String get() = str("carName", "FT"); set(v) = set("carName", v)
    /** Persist one virtual-HU ID for the lifetime of this app install/upgrade. */
    val aaVehicleId: String
        get() = synchronized(sp) {
            val existing = str("aaVehicleId", "")
            if (existing.isNotBlank()) existing else {
                val created = "FT-" + java.util.UUID.randomUUID().toString()
                // If storage fails, don't silently rotate the identity every session.
                check(sp.edit().putString("aaVehicleId", created).commit()) {
                    "Cannot persist Android Auto vehicle identity"
                }
                created
            }
        }
    var autoConnect: Boolean get() = bool("autoConnect", false); set(v) = set("autoConnect", v)
    var carProtocol: CarProtocol get() = CarProtocol.fromId(str("carProtocol", "carlife")); set(v) = set("carProtocol", v.id)
    /** Explicit GPS origin: CAR for ICCOA by default, PHONE for Baidu by default. */
    var gpsSourceMode: GpsSourceMode
        get() = GpsSourceMode.fromId(str("gpsSourceMode", GpsSourceMode.defaultFor(carProtocol).id))
        set(v) = set("gpsSourceMode", v.id)
    var carP2pName: String get() = str("carP2pName", ""); set(v) = set("carP2pName", v)
    var carBtName: String get() = str("carBtName", ""); set(v) = set("carBtName", v)
    var carBtAddress: String get() = str("carBtAddress", ""); set(v) = set("carBtAddress", v)
    var carBtReconnect: Boolean get() = bool("carBtReconnect", false); set(v) = set("carBtReconnect", v)
    /** Consent to save exact vehicle coordinates in FT diagnostic logs; off by default. */
    var vehicleCoordinateLogging: Boolean
        get() = bool("vehicleCoordinateLogging", false)
        set(v) {
            set("vehicleCoordinateLogging", v)
            // Disabling location-bearing diagnostics must discard pending IMU/GPS trace.
            if (!v) VehicleTraceRecorder.clear()
        }
    var iccoaMicGain: Int
        get() = Pcm16Gain.clamp(int("iccoaMicGain", 1))
        set(v) = set("iccoaMicGain", Pcm16Gain.clamp(v))
    /** ICCOA HU compatibility: optional 15s reassert of active OEM GPS request; OFF by default. */
    var iccoaSensorRefresh: Boolean get() = bool("iccoaSensorRefresh", false); set(v) = set("iccoaSensorRefresh", v)
    /** ICCOA only: 0 = normal FT composition, 1 = opt-in AA H.264 passthrough trial. */
    var iccoaVideoMode: Int get() = int("iccoaVideoMode", 0).coerceIn(0, 1); set(v) = set("iccoaVideoMode", v.coerceIn(0, 1))
    /** Only a cryptographically verified OEM EC public-key fingerprint may scope a car setting. */
    fun iccoaVideoModeFor(receiverId: String?): Int {
        val id = receiverId?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: return iccoaVideoMode
        return int("iccoaVideoMode.receiver.$id", iccoaVideoMode).coerceIn(0, 1)
    }
    fun setIccoaVideoModeFor(receiverId: String?, mode: Int) {
        val id = receiverId?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (id == null) iccoaVideoMode = mode
        else set("iccoaVideoMode.receiver.$id", mode.coerceIn(0, 1))
    }
    var swapTrackKeys: Boolean get() = bool("swapTrackKeys", false); set(v) = set("swapTrackKeys", v)
    var guidanceMode: Int get() = int("directions", CarAudioBus.GUIDANCE_UNTOUCHED); set(v) = set("directions", v)
    var linkMode: Int get() = int("linkMode", 0); set(v) = set("linkMode", v)
    var carWpsPin: String get() = str("carWpsPin", ""); set(v) = set("carWpsPin", v)
    var carJoinTimeoutSeconds: Int
        get() = JoinWatch.timeoutSeconds(int("carJoinTimeoutSeconds", JoinWatch.DEFAULT_TIMEOUT_SECONDS))
        set(v) = set("carJoinTimeoutSeconds", JoinWatch.timeoutSeconds(v))

    var mapsPackage: String get() = str("pkgMaps", "com.google.android.apps.maps"); set(v) = set("pkgMaps", v)
    var musicPackage: String get() = str("pkgMusic", "com.spotify.music"); set(v) = set("pkgMusic", v)
    var videoPackage: String get() = str("pkgVideo", "com.teamsmart.videomanager.tv"); set(v) = set("pkgVideo", v)
    // Resolve the actual default dialer on this Android phone, never assume Samsung.
    // Previously saved explicit user package choices remain untouched.
    var phonePackage: String
        get() = str("pkgPhone", runCatching {
            appContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
                .orEmpty()
        }.getOrDefault(""))
        set(v) = set("pkgPhone", v)

    var carInfoRight: Boolean get() = bool("carInfoRight", false); set(v) = set("carInfoRight", v)
    var carColumns: Int get() = int("carColumns", 0); set(v) = set("carColumns", v)
    var carShowClock: Boolean get() = bool("carShowClock", true); set(v) = set("carShowClock", v)
    var carShowMusic: Boolean get() = bool("carShowMusic", true); set(v) = set("carShowMusic", v)
    var carShowAa: Boolean get() = bool("carShowAa", true); set(v) = set("carShowAa", v)
    var carShowCarHome: Boolean get() = bool("carShowCarHome", true); set(v) = set("carShowCarHome", v)

    var carWidgets: String get() = str("carWidgets", ""); set(v) = set("carWidgets", v)
    var weatherFahrenheit: Boolean get() = bool("weatherFahrenheit", false); set(v) = set("weatherFahrenheit", v)
    var speedMph: Boolean get() = bool("speedMph", false); set(v) = set("speedMph", v)
    var lastLatitude: Float get() = flt("lastLatitude", 0f); set(v) = set("lastLatitude", v)
    var lastLongitude: Float get() = flt("lastLongitude", 0f); set(v) = set("lastLongitude", v)

    var carDayNight: Boolean get() = bool("carDayNight", false); set(v) = set("carDayNight", v)
    var carNightBackground: String get() = str("carNightBackground", "black"); set(v) = set("carNightBackground", v)
    var carNightAccent: Int get() = int("carNightAccent", 0); set(v) = set("carNightAccent", v)

    var wheelLongNext: Int get() = int("wheelLongNext", 0); set(v) = set("wheelLongNext", v)
    var wheelLongPrev: Int get() = int("wheelLongPrev", 0); set(v) = set("wheelLongPrev", v)
    var gearReverse: Int get() = int("gearReverse", 1); set(v) = set("gearReverse", v)
    var gearPark: Int get() = int("gearPark", 0); set(v) = set("gearPark", v)
    var gearReverseSound: Int get() = int("gearReverseSound", 0); set(v) = set("gearReverseSound", v)
    var gearParkSound: Int get() = int("gearParkSound", 0); set(v) = set("gearParkSound", v)
    var gearReverseLevel: Int get() = int("gearReverseLevel", 20); set(v) = set("gearReverseLevel", v)
    var gearParkLevel: Int get() = int("gearParkLevel", 20); set(v) = set("gearParkLevel", v)

    var openOnConnect: String get() = str("openOnConnect", ""); set(v) = set("openOnConnect", v)
    var openOnConnectSecond: String get() = str("openOnConnectSecond", ""); set(v) = set("openOnConnectSecond", v)

    var weatherCelsius: Float get() = flt("weatherCelsius", 0f); set(v) = set("weatherCelsius", v)
    var weatherCode: Int get() = int("weatherCode", -1); set(v) = set("weatherCode", v)
    var weatherAt: Long get() = lng("weatherAt", 0L); set(v) = set("weatherAt", v)

    var supercastToken: String get() = str("supercastToken", ""); set(v) = set("supercastToken", v)
    var supercastOn: Boolean get() = bool("supercastOn", false); set(v) = set("supercastOn", v)
    var supercastScreenOff: Boolean get() = bool("supercastScreenOff", true); set(v) = set("supercastScreenOff", v)
    /** Geometry mapping is the default. Existing calibration is opt-in for unusual hardware. */
    var mirrorUseCalibration: Boolean get() = bool("mirrorUseCalibration", false); set(v) = set("mirrorUseCalibration", v)
    var mirrorDimPhone: Boolean get() = bool("mirrorDimPhone", true); set(v) = set("mirrorDimPhone", v)
    var mirrorLandscapeApps: Boolean get() = bool("mirrorLandscapeApps", false); set(v) = set("mirrorLandscapeApps", v)
    var supercastApps: String get() = str("supercastApps", ""); set(v) = set("supercastApps", v)
    var supercastWay: Int get() = int("supercastWay", 0); set(v) = set("supercastWay", v)
    var supercastLegacyAdb: Boolean get() = bool("supercastLegacyAdb", false); set(v) = set("supercastLegacyAdb", v)
    var supercastDockHidden: Boolean get() = bool("supercastDockHidden", false); set(v) = set("supercastDockHidden", v)
    var supercastSplit: Float get() = flt("supercastSplit", 0.5f); set(v) = set("supercastSplit", v)
}

// Settings and the service each own a Prefs instance, but must invalidate/save atomically.
private val videoProfileLock = Any()
