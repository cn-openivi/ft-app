package app.ft.aa

/** The video surface bounds in the car display's content coordinates. */
data class AaViewport(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun contains(x: Int, y: Int): Boolean =
        width > 0 && height > 0 && x >= left && x < left + width && y >= top && y < top + height

    fun map(x: Int, y: Int, targetWidth: Int, targetHeight: Int): Pair<Int, Int> =
        (((x - left).toLong() * targetWidth / width.coerceAtLeast(1)).toInt()
            .coerceIn(0, (targetWidth - 1).coerceAtLeast(0))) to
        (((y - top).toLong() * targetHeight / height.coerceAtLeast(1)).toInt()
            .coerceIn(0, (targetHeight - 1).coerceAtLeast(0)))
}

object AaTouchViewport {
    @Volatile var bounds: AaViewport? = null
}


/**
 * Freeze both physical viewport and negotiated AA input dimensions at DOWN.
 * A rotation or layout remeasurement during a drag must not remap MOVE/UP
 * into a different coordinate space. New geometry applies to the next DOWN.
 */
internal class AaTouchGestureViewport {
    private var active: AaViewport? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var lastMapped: Pair<Int, Int>? = null

    fun map(
        action: Int, x: Int, y: Int,
        viewport: AaViewport?, width: Int, height: Int
    ): Pair<Int, Int>? {
        if (action !in 0..2) {
            reset()
            return null
        }
        if (action == 0) {
            reset()
            if (viewport == null || !viewport.contains(x, y) || width <= 0 || height <= 0)
                return null
            active = viewport.copy()
            targetWidth = width
            targetHeight = height
        }
        val captured = active ?: return null
        val mapped = captured.map(x, y, targetWidth, targetHeight)
        lastMapped = mapped
        if (action == 1) reset()
        return mapped
    }

    /** Emit a real AA CANCEL using the DOWN geometry; never fake a tap-producing UP. */
    fun cancel(x: Int, y: Int): Pair<Int, Int>? {
        val captured = active ?: return null
        val mapped = captured.map(x, y, targetWidth, targetHeight)
        reset()
        return mapped
    }

    /** Route teardown sends CANCEL at the last accepted AA point, not an UP. */
    fun cancelActive(): Pair<Int, Int>? {
        val point = if (active == null) null else lastMapped
        reset()
        return point
    }

    fun reset() {
        active = null
        lastMapped = null
    }
}
