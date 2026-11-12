package app.ft.aa

/**
 * Optional read-only access to the compressed Android Auto video stream.
 * The normal decoder remains the default. Never block the AA socket reader
 * or delay AV_MEDIA_ACK while a downstream transport is slow.
 */
internal object AaH264Tap {
    interface Listener {
        fun onStart(source: AaSession)
        fun onConfig(source: AaSession, csd: ByteArray)
        /** True only after the receiver has consumed this access unit directly. */
        fun onFrame(source: AaSession, frame: ByteArray, timestamp: Long): Boolean
        fun onStop(source: AaSession)
    }

    @Volatile var listener: Listener? = null

    private fun selected(source: AaSession): Listener? =
        listener?.takeIf { AaHeadUnitService.current === source }

    fun start(source: AaSession) { selected(source)?.onStart(source) }
    fun config(source: AaSession, csd: ByteArray) { selected(source)?.onConfig(source, csd) }
    fun frame(source: AaSession, data: ByteArray, timestamp: Long): Boolean =
        selected(source)?.onFrame(source, data, timestamp) == true
    fun stop(source: AaSession) { selected(source)?.onStop(source) }
}
