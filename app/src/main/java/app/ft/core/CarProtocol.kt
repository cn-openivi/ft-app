package app.ft.core

/**
 * Stable OEM projection transport IDs. Android Auto is content, not a fourth HU transport.
 * A declared or UI-selected protocol is NOT proof its real wire adapter is ready.
 * CarPlay stays connectionReady=false even when its experimental UI entry is enabled.
 */
enum class CarProtocol(val id: String, val connectionReady: Boolean = true) {
    CARLIFE("carlife"), ICCOA("iccoa"), CARPLAY("carplay", connectionReady = false);

    companion object {
        /** Strict explicit parsing; unknown transports must never be dispatched as Baidu. */
        fun fromExplicitId(id: String?): CarProtocol? = entries.firstOrNull { it.id == id }

        /** Legacy preference/backup migration only; older defaults remain unchanged. */
        fun fromId(id: String?): CarProtocol = fromExplicitId(id) ?: CARLIFE
    }
}
