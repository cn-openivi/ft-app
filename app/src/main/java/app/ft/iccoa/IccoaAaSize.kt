package app.ft.iccoa

import app.ft.aa.AaProtocol

/**
 * Choose a real Android Auto VIDEO raster. A car may be wider than 16:9;
 * that physical aspect is handled by the compositor/viewport, not by
 * inventing a narrower AA touch canvas such as 1920x1008.
 */
internal object IccoaAaSize {
    fun fit(width: Int, height: Int): Pair<Int, Int> =
        AaProtocol.standardSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
}
