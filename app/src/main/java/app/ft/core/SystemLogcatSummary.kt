package app.ft.core

/**
 * Coordinate-free diagnostic projection of logcat when raw location recording is OFF.
 *
 * System logcat is untrusted free-form text: OEM packages may print coordinates,
 * user identifiers, raw BLE/ICCOA payloads and arbitrary app content. Never
 * export a message, exception detail, stack trace, or caller-provided tag.
 * Emit only header metadata with a fixed tag and fixed event classification.
 */
internal object SystemLogcatSummary {
    private val header = Regex(
        """^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([A-Za-z0-9_.-]+)\\s*:\\s*(.*)$"""
    )
    private val approvedTags = setOf(
        "MediaCodec", "CCodec", "ACodec", "Codec2Client", "AudioRecord",
        "AudioFlinger", "AudioPolicyManager", "BufferQueueProducer",
        "Surface", "SurfaceUtils", "AndroidRuntime", "ActivityManager",
        "MediaProjection", "DisplayManager", "WifiP2pService",
        "BluetoothManagerService", "BluetoothAdapter", "libc"
    )
    private const val MAX_LINES = 1500

    fun summarize(raw: String): String {
        val events = ArrayDeque<String>(MAX_LINES)
        var seen = 0L
        raw.lineSequence().forEach { line ->
            val match = header.matchEntire(line) ?: return@forEach
            val tag = match.groupValues[5]
            if (tag !in approvedTags) return@forEach
            val message = match.groupValues[6]
            val kind = when {
                message.contains("obsolete surface", ignoreCase = true) -> "OBSOLETE_SURFACE"
                message.contains("BufferQueue has been abandoned", ignoreCase = true) -> "BUFFER_QUEUE_ABANDONED"
                message.contains("CodecException", ignoreCase = true) -> "CODEC_EXCEPTION"
                message.contains("FATAL EXCEPTION", ignoreCase = true) -> "FATAL_EXCEPTION"
                message.contains("ANR in", ignoreCase = true) -> "ANR"
                message.contains("ERROR_DEAD_OBJECT", ignoreCase = true) -> "AUDIO_DEAD_OBJECT"
                message.contains("ERROR_BAD_VALUE", ignoreCase = true) -> "AUDIO_BAD_VALUE"
                message.contains("dequeue", ignoreCase = true) -> "DEQUEUE_EVENT"
                message.contains("setOutputSurface", ignoreCase = true) -> "OUTPUT_SURFACE_EVENT"
                else -> "OTHER"
            }
            seen++
            val safe = "${match.groupValues[1]} pid=${match.groupValues[2]} " +
                "tid=${match.groupValues[3]} level=${match.groupValues[4]} tag=$tag event=$kind"
            if (events.size == MAX_LINES) events.removeFirst()
            events.addLast(safe)
        }
        return buildString {
            append("SYSTEM_LOGCAT_METADATA_ONLY version=1 no_raw_messages=true no_coordinates=true\\n")
            append("matched_events=$seen retained_events=${events.size} cap=$MAX_LINES\\n")
            for (line in events) append(line).append('\\n')
        }
    }
}
