package app.ft.aa

/** Do not sacrifice AA control, video focus/ACK, microphone or OEM GPS
 * merely because high-rate optional UI messages filled the off-main queue.
 */
internal object AaSendPriority {
    fun critical(channel: Int, id: Int): Boolean =
        channel == AaProtocol.CH_CONTROL ||
        channel == AaProtocol.CH_SENSOR ||
        channel == AaProtocol.CH_VIDEO ||
        channel == AaProtocol.CH_AV_INPUT ||
        id == AaProtocol.AV_MEDIA_ACK ||
        id == AaProtocol.AV_SETUP_RESPONSE ||
        id == AaProtocol.CHANNEL_OPEN_RESPONSE
}
