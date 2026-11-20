package app.ft.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Explicit, single-run, straight-road calibration. It never modifies ECU/speedometer
 * or trains the coefficient from ordinary navigation fixes. Wheel input is the
 * field-confirmed FAW GearInfo speed (km/h); independent GPS is Android GPS_PROVIDER
 * on the phone, admitted only with actual accuracy/fix-clock metadata.
 */
internal class WheelCalibrationSession {
    enum class Phase { IDLE, WAITING, MEASURING, SAVING, COMPLETE, TIMED_OUT, UNAVAILABLE }
    data class Status(
        val phase: Phase = Phase.IDLE,
        val targetMetres: Int = 2_000,
        val measuredMetres: Int = 0,
        val multiplier: Double? = null,
        val reason: String = ""
    )
    data class Gnss(
        val fixMonotonicMs: Long,
        val receivedMonotonicMs: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMetres: Double?,
        val speedMps: Double?,
        val speedAccuracyMps: Double?,
        val bearingDegrees: Double?,
        val provider: String
    )
    private data class Point(
        val fix: Gnss, val wheelMetres: Double
    )

    var status = Status()
        private set

    private var startedMs = 0L
    private var lastGearMs = 0L
    private var lastSpeedMps = 0.0
    private var accumulatedWheelMetres = 0.0
    private var origin: Point? = null
    private var midpoint: Point? = null
    private var lastFix: Point? = null
    private var fixCount = 0
    private var firstBearing: Double? = null

    @Synchronized fun begin(km: Int, nowMs: Long): Status {
        require(km == 2 || km == 5)
        require(nowMs > 0)
        resetSegment()
        startedMs = nowMs
        status = Status(Phase.WAITING, km * 1_000, reason = "Ожидание прямого открытого участка и хорошего GPS")
        return status
    }

    @Synchronized fun timeCheck(nowMs: Long): Status {
        if (status.phase in listOf(Phase.WAITING, Phase.MEASURING) &&
            nowMs >= startedMs && nowMs - startedMs >= 20 * 60_000L) {
            resetSegment()
            status = status.copy(phase = Phase.TIMED_OUT, measuredMetres = 0,
                reason = "Не найден подходящий участок за 20 минут")
        }
        return status
    }

    @Synchronized fun onGear(speedKmh: Double?, gearRaw: Long?, atMs: Long): Status {
        timeCheck(atMs)
        if (status.phase !in listOf(Phase.WAITING, Phase.MEASURING)) return status
        val speed = speedKmh?.div(3.6)
        if (gearRaw != 2L || speed == null || !speed.isFinite() ||
            speed !in 10.0..39.0 || atMs <= 0L) {
            rejectSegment()
            return status
        }
        if (lastGearMs != 0L) {
            val dt = atMs - lastGearMs
            if (dt !in 20L..2_000L ||
                abs(speed - lastSpeedMps) / (dt / 1_000.0) > 0.7) {
                rejectSegment()
            } else if (origin != null) {
                accumulatedWheelMetres += 0.5 * (speed + lastSpeedMps) * dt / 1_000.0
            }
        }
        lastGearMs = atMs
        lastSpeedMps = speed
        return status
    }

    @Synchronized fun onGnss(fix: Gnss): Status {
        timeCheck(fix.receivedMonotonicMs)
        if (status.phase !in listOf(Phase.WAITING, Phase.MEASURING)) return status
        if (!validFix(fix) || lastGearMs == 0L ||
            abs(fix.fixMonotonicMs - lastGearMs) > 1_200L ||
            abs(lastSpeedMps - requireNotNull(fix.speedMps)) > 2.0) {
            rejectSegment()
            return status
        }
        val wheelAtFix = accumulatedWheelMetres +
            lastSpeedMps * (fix.fixMonotonicMs - lastGearMs) / 1_000.0
        val point = Point(fix, wheelAtFix)
        val first = origin
        if (first == null) {
            origin = point
            lastFix = point
            firstBearing = fix.bearingDegrees
            fixCount = 1
            status = status.copy(phase = Phase.MEASURING, measuredMetres = 0,
                reason = "Измерение по прямой")
            return status
        }
        val previous = lastFix ?: return status
        val timeGap = fix.fixMonotonicMs - previous.fix.fixMonotonicMs
        val wheelDelta = wheelAtFix - previous.wheelMetres
        val gnssDelta = distanceMetres(previous.fix, fix)
        val totalWheel = wheelAtFix - first.wheelMetres
        val bearing = fix.bearingDegrees
        val initialBearing = firstBearing
        if (timeGap !in 200L..2_500L || wheelDelta <= 0 ||
            gnssDelta > wheelDelta * 1.18 + 5.0 ||
            gnssDelta + 5.0 < wheelDelta * 0.82 ||
            bearing == null || initialBearing == null ||
            angleDifference(bearing, initialBearing) > 3.0 ||
            totalWheel > 0.0 && crossTrackDeviation(first.fix, fix, initialBearing) >
                5.0 + totalWheel * 0.005) {
            rejectSegment()
            return status
        }
        fixCount++
        lastFix = point
        if (midpoint == null && totalWheel >= status.targetMetres / 2.0) midpoint = point
        status = status.copy(measuredMetres = min(totalWheel.toInt(), status.targetMetres))
        if (totalWheel < status.targetMetres || fixCount < 20) return status

        val middle = midpoint
        if (middle == null) { rejectSegment(); return status }
        val firstWheel = middle.wheelMetres - first.wheelMetres
        val secondWheel = point.wheelMetres - middle.wheelMetres
        val firstRatio = distanceMetres(first.fix, middle.fix) / firstWheel
        val secondRatio = distanceMetres(middle.fix, fix) / secondWheel
        val candidate = distanceMetres(first.fix, fix) / totalWheel
        if (listOf(firstRatio, secondRatio, candidate).any { !it.isFinite() || it !in 0.90..1.10 } ||
            abs(firstRatio - secondRatio) > 0.01 * candidate ||
            abs(candidate - 0.5 * (firstRatio + secondRatio)) > 0.01) {
            rejectSegment()
            return status
        }
        status = status.copy(phase = Phase.SAVING, measuredMetres = status.targetMetres,
            multiplier = candidate, reason = "Проверено, сохранение результата")
        return status
    }

    @Synchronized fun finishSave(saved: Boolean): Status {
        if (status.phase != Phase.SAVING) return status
        status = status.copy(
            phase = if (saved) Phase.COMPLETE else Phase.UNAVAILABLE,
            reason = if (saved) "Калибровка завершена, коэффициент зафиксирован"
                     else "Не удалось надёжно сохранить коэффициент"
        )
        resetSegment()
        return status
    }

    @Synchronized fun stopUnavailable(reason: String): Status {
        if (status.phase in listOf(Phase.WAITING, Phase.MEASURING, Phase.SAVING)) {
            resetSegment()
            status = status.copy(phase = Phase.UNAVAILABLE, measuredMetres = 0, reason = reason)
        }
        return status
    }

    private fun rejectSegment() {
        resetSegment()
        status = status.copy(phase = Phase.WAITING, measuredMetres = 0,
            reason = "Ищем следующий прямой участок с хорошим GPS")
    }
    private fun resetSegment() {
        lastGearMs = 0
        lastSpeedMps = 0.0
        accumulatedWheelMetres = 0.0
        origin = null
        midpoint = null
        lastFix = null
        firstBearing = null
        fixCount = 0
    }
    private fun validFix(f: Gnss): Boolean =
        f.provider == "gps" && f.fixMonotonicMs > 0L &&
        f.receivedMonotonicMs >= f.fixMonotonicMs &&
        f.receivedMonotonicMs - f.fixMonotonicMs <= 1_200L &&
        f.latitude.isFinite() && f.latitude in -85.0..85.0 &&
        f.longitude.isFinite() && f.longitude in -180.0..180.0 &&
        f.accuracyMetres != null && f.accuracyMetres in 0.5..3.0 &&
        f.speedMps != null && f.speedMps in 10.0..39.0 &&
        f.speedAccuracyMps != null && f.speedAccuracyMps in 0.0..0.8 &&
        f.bearingDegrees != null && f.bearingDegrees in 0.0..360.0

    private fun angleDifference(a: Double, b: Double) =
        abs(((a - b + 540.0) % 360.0) - 180.0)

    private fun crossTrackDeviation(a: Gnss, b: Gnss, bearingDegrees: Double): Double {
        val latR = Math.toRadians(a.latitude)
        val east = Math.toRadians(b.longitude - a.longitude) * 6_371_008.8 * cos(latR)
        val north = Math.toRadians(b.latitude - a.latitude) * 6_371_008.8
        val heading = Math.toRadians(bearingDegrees)
        return abs(east * cos(heading) - north * sin(heading))
    }

    private fun distanceMetres(a: Gnss, b: Gnss): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
