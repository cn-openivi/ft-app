package app.ft.aa

/**
 * The UI presents quality tiers, never arbitrary pixels. The AA protocol
 * still needs the exact enumerated geometry for service discovery.
 *
 * This is independent from ICCOA WFD decoder negotiation, AVC Profile/Level
 * and Android Auto's user interface density.
 */
internal enum class AaResolutionTier(val width: Int, val height: Int) {
    AUTO(0, 0),
    P480(800, 480),
    P720(1280, 720),
    P1080(1920, 1080);

    companion object {
        /**
         * null means a legacy custom geometry no longer expressible by the UI.
         * It is migrated to AUTO by the settings page, not misrepresented as 720p.
         */
        fun fromStored(matchCar: Boolean, width: Int, height: Int): AaResolutionTier? =
            if (matchCar) AUTO else entries.firstOrNull {
                it != AUTO && it.width == width && it.height == height
            }
    }
}
