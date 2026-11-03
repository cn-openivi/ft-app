package app.ft.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared protocol-neutral raw vehicle IMU stream. ICCOA and Baidu are input
 * adapters; only a separate, explicitly validated DR engine may interpret
 * axes, units and coordinate frames. Never substitute handset IMU silently.
 */
enum class MotionSource { ICCOA, BAIDU }
enum class MotionKind { GYROSCOPE, ACCELERATION }

data class MotionVector(
    val x: Double,
    val y: Double,
    val z: Double,
    val deviceTimestampRaw: Long? = null,
    val gyroType: Int? = null
)

data class MotionReading(
    val vector: MotionVector? = null,
    val accepted: Long = 0,
    val rejected: Long = 0,
    val lastFrameAtMs: Long = 0,
    val lastValidAtMs: Long = 0,
    val approximateHz: Double? = null
) {
    fun fresh(nowMs: Long): Boolean =
        vector != null && lastValidAtMs > 0L && nowMs >= lastValidAtMs &&
            nowMs - lastValidAtMs <= 3_000L

    fun ageMs(nowMs: Long): Long? =
        lastValidAtMs.takeIf { it > 0L }?.let { (nowMs - it).coerceAtLeast(0L) }
}

data class MotionSnapshot(
    val gyro: MotionReading = MotionReading(),
    val acceleration: MotionReading = MotionReading()
) {
    fun get(kind: MotionKind): MotionReading =
        if (kind == MotionKind.GYROSCOPE) gyro else acceleration
    fun replace(kind: MotionKind, value: MotionReading): MotionSnapshot =
        if (kind == MotionKind.GYROSCOPE) copy(gyro = value) else copy(acceleration = value)
}

object VehicleMotionTelemetry {
    private const val UI_PERIOD_MS = 250L
    private val _current = MutableStateFlow<Map<MotionSource, MotionSnapshot>>(emptyMap())
    val current: StateFlow<Map<MotionSource, MotionSnapshot>> = _current
    private val snapshots = mutableMapOf<MotionSource, MotionSnapshot>()
    private val lastPublishedAt = mutableMapOf<MotionSource, Long>()

    /** Every received frame is accounted for; only UI snapshots are throttled. */
    @Synchronized fun accept(
        source: MotionSource,
        kind: MotionKind,
        vector: MotionVector?,
        nowMs: Long = System.currentTimeMillis()
    ): MotionReading {
        val current = snapshots[source] ?: MotionSnapshot()
        val before = current.get(kind)
        val interval = if (vector != null && before.lastValidAtMs > 0 &&
            nowMs > before.lastValidAtMs) nowMs - before.lastValidAtMs else 0L
        val observedHz = if (interval in 1L..5_000L) 1000.0 / interval else null
        val nextHz = if (observedHz == null) before.approximateHz else
            before.approximateHz?.let { it * 0.8 + observedHz * 0.2 } ?: observedHz
        val updated = before.copy(
            vector = vector ?: before.vector,
            accepted = before.accepted + if (vector != null) 1 else 0,
            rejected = before.rejected + if (vector == null) 1 else 0,
            lastFrameAtMs = nowMs,
            lastValidAtMs = if (vector != null) nowMs else before.lastValidAtMs,
            approximateHz = nextHz
        )
        snapshots[source] = current.replace(kind, updated)
        val lastUi = lastPublishedAt[source]
        if (lastUi == null || nowMs < lastUi || nowMs - lastUi >= UI_PERIOD_MS) {
            _current.value = snapshots.toMap()
            lastPublishedAt[source] = nowMs
        }
        return updated
    }

    @Synchronized fun clear(source: MotionSource) {
        snapshots.remove(source)
        lastPublishedAt.remove(source)
        _current.value = snapshots.toMap()
    }
}
