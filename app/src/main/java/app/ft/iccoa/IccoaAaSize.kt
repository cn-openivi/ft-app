package app.ft.iccoa

import app.ft.aa.AaProtocol

/**
 * Choose a real Android Auto VIDEO raster using the negotiated WFD
 * VIDEO stream dimensions, NOT the Toyota physical panel or the full
 * FT virtual-display aspect. The visible AA viewport is measured by
 * AaOverlay, and its touch bounds are handled separately. No 1920x1008.
 */
internal object IccoaAaSize {
    fun fit(width: Int, height: Int): Pair<Int, Int> =
        AaProtocol.standardSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
}
