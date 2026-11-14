package app.ft.aa

/**
 * User-facing quality targets, independent of OEM brand, phone vendor and
 * physical screen dimensions.
 *
 * The current verified AA VIDEO wire path supports only 480p/720p/1080p.
 * Higher modes exist in some reverse-engineered AA enum snapshots, but many
 * runtime implementations still reject them. Never advertise unknown wire
 * values or mismatch AA H.264 raster and input/touch coordinates.
 *
 * 2K DCI (2048x1080) differs from 1440p/QHD (2560x1440). There is no
 * independently verified AA 2K-DCI video-resolution wire enum.
 */
internal enum class AaResolutionTier(
    val width: Int,
    val height: Int,
    val verifiedWire: Boolean
) {
    AUTO(0, 0, true),
    P480(800, 480, true),
    P720(1280, 720, true),
    P1080(1920, 1080, true),
    P2K_DCI(2048, 1080, false),
    P1440(2560, 1440, false),
    P4K_UHD(3840, 2160, false);

    /**
     * Preserve requested high tier in preferences, but don't send unsupported
     * AA VIDEO enums or fake 4K coordinates. The current transport safely
     * advertises a valid 1080p candidate until high-mode protocol admission
     * is verified independently in a later implementation.
     */
    val effectiveWireSize: Pair<Int, Int>
        get() = when {
            this == AUTO -> 1280 to 720 // neutral before receiver negotiation
            verifiedWire -> width to height
            else -> 1920 to 1080
        }

    val isExperimental: Boolean get() = !verifiedWire

    companion object {
        /**
         * A legacy exact size can be mapped only to a verified wire tier.
         * Invalid custom dimensions should migrate to receiver-aware AUTO.
         */
        fun fromStored(matchCar: Boolean, width: Int, height: Int): AaResolutionTier? =
            if (matchCar) AUTO else entries.firstOrNull {
                it != AUTO && it.verifiedWire && it.width == width && it.height == height
            }

        /** New explicit user intent is saved separately from effective wire size. */
        fun fromSavedKey(key: String): AaResolutionTier? =
            entries.firstOrNull { it.name == key }
    }
}
