package app.ft.dr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import app.ft.FTApp
import app.ft.core.AndroidWheelCalibrationStorage
import app.ft.core.WheelCalibrationSession
import app.ft.core.WheelCalibrationStore
import app.ft.core.WheelSpeedAutoCalibration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Only local FT app state changes. Android GPS_PROVIDER is a temporary
 * independent calibration reference; it is NOT a navigation fallback and
 * its coordinates are never written into ordinary diagnostics.
 */
internal object WheelCalibrationRuntime {
    private val session = WheelCalibrationSession()
    private val _status = MutableStateFlow(session.status)
    val status: StateFlow<WheelCalibrationSession.Status> = _status
    private var vehicleId: String? = null
    private var store: WheelCalibrationStore? = null
    private var coefficient: Double? = null
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private var generation = 0L
    private var saving = false
    private var pendingStartKm: Int? = null

    @Synchronized fun bindVerifiedCar(context: Context, authenticatedId: String) {
        if (authenticatedId.isBlank() || authenticatedId.length > 256) return
        if (vehicleId != null && vehicleId != authenticatedId) {
            stop("Сменился автомобиль. Калибровка остановлена")
        }
        vehicleId = authenticatedId
        store = WheelCalibrationStore(AndroidWheelCalibrationStorage(context.applicationContext))
        coefficient = store?.restore(authenticatedId)?.multiplier
        val pending = pendingStartKm
        if (pending != null) {
            pendingStartKm = null
            _status.value = WheelCalibrationSession.Status()
            start(context, pending)
        }
    }

    @Synchronized fun onCarDisconnect() {
        // A user-armed session may wait for ICCOA connection without requiring
        // a second tap. Do not cancel that pending request during discovery.
        if (pendingStartKm != null) {
            vehicleId = null
            store = null
            coefficient = null
            return
        }
        // Do not mix two authenticated sessions into one straight-road segment.
        if (isActive()) {
            stop("Соединение с автомобилем прервано")
        }
        vehicleId = null
        store = null
        coefficient = null
    }

    @Synchronized fun correctedSpeedFactor(): Double = coefficient ?: 1.0
    @Synchronized fun savedCoefficient(): Double? = coefficient

    @Synchronized fun start(context: Context, kilometres: Int): Boolean {
        if (kilometres != 2 && kilometres != 5) return false
        if (isActive()) return false
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            _status.value = WheelCalibrationSession.Status(
                WheelCalibrationSession.Phase.UNAVAILABLE, kilometres * 1_000,
                reason = "Разрешите точную геолокацию телефону")
            return false
        }
        val id = vehicleId
        if (id == null || store == null) {
            pendingStartKm = kilometres
            val pendingGeneration = ++generation
            _status.value = WheelCalibrationSession.Status(
                WheelCalibrationSession.Phase.WAITING, kilometres * 1_000,
                reason = "Ожидаем подключение FT к автомобилю")
            FTApp.instance.scope.launch {
                delay(20 * 60_000L)
                synchronized(WheelCalibrationRuntime) {
                    if (generation == pendingGeneration && pendingStartKm == kilometres) {
                        pendingStartKm = null
                        _status.value = WheelCalibrationSession.Status(
                            WheelCalibrationSession.Phase.TIMED_OUT, kilometres * 1_000,
                            reason = "Подключение не появилось за 20 минут")
                    }
                }
            }
            return true
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            _status.value = WheelCalibrationSession.Status(
                WheelCalibrationSession.Phase.UNAVAILABLE, kilometres * 1_000,
                reason = "Включите GPS телефона")
            return false
        }
        generation++
        val currentGeneration = generation
        session.begin(kilometres, SystemClock.elapsedRealtime())
        val gpsListener = LocationListener { location -> onGps(location, currentGeneration) }
        val subscribed = runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f,
                gpsListener, Looper.getMainLooper())
            true
        }.getOrDefault(false)
        if (!subscribed) {
            _status.value = session.stopUnavailable("Телефон не предоставил GPS")
            return false
        }
        manager = lm
        listener = gpsListener
        _status.value = session.status
        FTApp.instance.scope.launch {
            delay(20 * 60_000L)
            synchronized(WheelCalibrationRuntime) {
                if (generation == currentGeneration && isActive()) {
                    _status.value = session.timeCheck(SystemClock.elapsedRealtime())
                    if (!isActive()) releaseGpsListener()
                }
            }
        }
        return true
    }

    @Synchronized fun onGear(speedKmh: Long?, gear: Long?, monotonicMs: Long) {
        if (!isActive() || saving) return
        _status.value = session.onGear(speedKmh?.toDouble(), gear, monotonicMs)
        if (!isActive()) releaseGpsListener()
    }

    @Synchronized private fun onGps(location: Location, expectedGeneration: Long) {
        if (expectedGeneration != generation || !isActive() || saving) return
        val now = SystemClock.elapsedRealtime()
        // A mock/simulated location must not calibrate real wheel distance.
        if (location.isMock) return
        _status.value = session.onGnss(WheelCalibrationSession.Gnss(
            fixMonotonicMs = location.elapsedRealtimeNanos / 1_000_000L,
            receivedMonotonicMs = now,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMetres = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            speedAccuracyMps = if (location.hasSpeedAccuracy())
                location.speedAccuracyMetersPerSecond.toDouble() else null,
            bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else null,
            provider = location.provider ?: ""
        ))
        if (_status.value.phase == WheelCalibrationSession.Phase.SAVING) {
            saving = true
            releaseGpsListener()
            val candidate = _status.value.multiplier
            val id = vehicleId
            val destination = store
            val saveGeneration = generation
            FTApp.instance.scope.launch(Dispatchers.IO) {
                val saved = id != null && candidate != null && destination != null &&
                    destination.persistSnapshot(WheelSpeedAutoCalibration.Snapshot(id, candidate))
                synchronized(WheelCalibrationRuntime) {
                    if (saveGeneration == generation) {
                        if (saved) {
                            coefficient = candidate
                            FtDrRuntime.applyWheelCalibrationSnapshot(
                                WheelSpeedAutoCalibration.Snapshot(requireNotNull(id), requireNotNull(candidate)))
                        }
                        _status.value = session.finishSave(saved)
                        saving = false
                    }
                }
            }
        }
    }

    @Synchronized fun stop(reason: String = "Калибровка остановлена") {
        generation++
        releaseGpsListener()
        val pending = pendingStartKm
        pendingStartKm = null
        _status.value = if (pending != null) WheelCalibrationSession.Status(
            WheelCalibrationSession.Phase.UNAVAILABLE, pending * 1_000, reason = reason
        ) else session.stopUnavailable(reason)
        saving = false
    }

    private fun isActive(): Boolean =
        _status.value.phase == WheelCalibrationSession.Phase.WAITING ||
        _status.value.phase == WheelCalibrationSession.Phase.MEASURING ||
        _status.value.phase == WheelCalibrationSession.Phase.SAVING

    private fun releaseGpsListener() {
        listener?.let { l -> runCatching { manager?.removeUpdates(l) } }
        listener = null
        manager = null
    }
}
