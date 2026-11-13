package app.ft.ui.car

/** Geometry for the Android Auto FT return overlay. Pure Kotlin; no Android device needed. */
internal object AaFloatingReturnPlacement {
    const val HOLD_MS = 4_000L

    data class Point(val x: Float, val y: Float)

    fun fromCorner(corner: Int): Point = when (corner) {
        1 -> Point(1f, 0f)
        2 -> Point(0f, 1f)
        3 -> Point(1f, 1f)
        else -> Point(0f, 0f)
    }

    fun dragBy(position: Point, dxPx: Float, dyPx: Float, travelX: Int, travelY: Int): Point =
        Point(
            if (travelX <= 0 || !dxPx.isFinite()) position.x else (position.x + dxPx / travelX).coerceIn(0f, 1f),
            if (travelY <= 0 || !dyPx.isFinite()) position.y else (position.y + dyPx / travelY).coerceIn(0f, 1f)
        )

    fun pixel(normalized: Float, extentPx: Int, buttonPx: Int, marginPx: Int): Int {
        val safeExtent = extentPx.coerceAtLeast(0)
        val safeButton = buttonPx.coerceIn(0, safeExtent)
        val inset = marginPx.coerceIn(0, (safeExtent - safeButton) / 2)
        val range = (safeExtent - safeButton - inset * 2).coerceAtLeast(0)
        val fraction = if (normalized.isFinite()) normalized.coerceIn(0f, 1f) else 0f
        return inset + (fraction * range).toInt()
    }
}
