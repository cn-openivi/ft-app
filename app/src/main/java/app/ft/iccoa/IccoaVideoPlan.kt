package app.ft.iccoa

import app.ft.projection.VideoPlan

/**
 * Keep receiver content geometry separate from its decoded H.264 stream raster.
 * Use a real M3/M4 WFD codec intersection when observed; otherwise retain the
 * known conservative Level 4.0 transport fallback until on-HU verification.
 */
internal object IccoaVideoPlan {
    fun from(config: IccoaVideoConfig): VideoPlan {
        val width = (config.width and -2).coerceAtLeast(320)
        val height = (config.height and -2).coerceAtLeast(240)
        val chosen = config.wfdAvc
        val streamWidth = chosen?.width ?: if (width > 1920 || height > 1080) 1920 else width
        val streamHeight = chosen?.height ?: if (width > 1920 || height > 1080) 1080 else height
        val fps = chosen?.fps ?: config.fps.coerceIn(1, 30)
        // No sink bitrate limit has been proved. The new 720p negotiated option
        // scales the old 8Mbps CBR baseline; do not advertise an invented ceiling.
        val bitrate = if (chosen == null) 8_000_000
            else (8_000_000L * streamWidth * streamHeight / (1920L * 1080L))
                .coerceIn(2_000_000L, 8_000_000L).toInt()
        return VideoPlan(width, height, streamWidth, streamHeight, fps, bitrate,
            constantBitrate = true, preserveAspect = streamWidth != width || streamHeight != height)
    }
}
