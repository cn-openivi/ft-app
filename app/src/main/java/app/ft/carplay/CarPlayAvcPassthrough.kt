package app.ft.carplay

import app.ft.iccoa.IccoaDirectAvc

/**
 * FT200.6 original AA compressed-AVC handoff. Reuses the existing FT190 SPS
 * parser; no decoder, encoder, transcoding or synthetic AirPlay video format.
 * A FUTURE AirPlay receiver must still verify peer packetization/encryption.
 *
 * Never claim that SOURCE_ONLY compatible NALs mean the Toyota displayed them.
 * The receiver callback MUST consume immediately and non-blockingly; its false
 * return means "busy/drop" without delaying AA MEDIA_ACK or queueing stale video.
 */
internal class CarPlayAvcPassthrough(
    private val maxDecodeGapUs: Long = 1_000_000L
) {
    init { require(maxDecodeGapUs in 50_000L..10_000_000L) }
    enum class Result {
        NO_CONFIG, CONFIG_READY_WAITING_IDR, SOURCE_SPS_INVALID, AIRPLAY_PEER_UNVERIFIED,
        FRAME_TOO_LARGE, TIMESTAMP_INVALID, NEED_IDR, NEED_IDR_AFTER_GAP,
        PROFILE_OR_SECURITY_MISMATCH, BACKPRESSURE_DROP,
        ORIGINAL_COMPRESSED_AU_CONSUMED
    }
    private var parsed: IccoaDirectAvc.Sps? = null
    private var selectedFps = 0
    private var negotiated: CarPlayMediaPreflight.Receiver? = null
    private var hasIdr = false
    private var previousPts = Long.MIN_VALUE

    @Synchronized
    fun configure(
        configuration: ByteArray,
        fps: Int,
        receiver: CarPlayMediaPreflight.Receiver?
    ): Result {
        reset()
        if (receiver == null) return Result.AIRPLAY_PEER_UNVERIFIED
        if (configuration.size > 65536 || fps !in 1..60) return Result.SOURCE_SPS_INVALID
        val annexB = IccoaDirectAvc.normalizedConfiguration(configuration)
            ?: return Result.SOURCE_SPS_INVALID
        val sps = IccoaDirectAvc.sps(annexB)
            ?: return Result.SOURCE_SPS_INVALID
        parsed = sps
        selectedFps = fps
        negotiated = receiver
        return Result.CONFIG_READY_WAITING_IDR // A real first IDR is still required
    }

    /** No frame is copied or retained. One synchronous, caller-owned attempt. */
    @Synchronized
    fun offer(
        encodedAccessUnit: ByteArray,
        ptsUs: Long,
        consumer: (ByteArray, Long) -> Boolean
    ): Result {
        val sps = parsed ?: return Result.NO_CONFIG
        val receiver = negotiated ?: return Result.AIRPLAY_PEER_UNVERIFIED
        if (encodedAccessUnit.isEmpty() || encodedAccessUnit.size > 4_194_304)
            return Result.FRAME_TOO_LARGE
        if (ptsUs < 0 || (previousPts != Long.MIN_VALUE && ptsUs <= previousPts))
            return Result.TIMESTAMP_INVALID
        val isIdr = IccoaDirectAvc.isIdr(encodedAccessUnit)
        // If a long stall occurred, the decoder reference chain is no longer
        // proven continuous. Demand a new keyframe before handing off P/B AUs.
        if (previousPts != Long.MIN_VALUE &&
            ptsUs - previousPts > maxDecodeGapUs) {
            hasIdr = false
            if (!isIdr) return Result.NEED_IDR_AFTER_GAP
        }
        if (!hasIdr && !isIdr) return Result.NEED_IDR
        val candidate = CarPlayMediaPreflight.Video(
            width = sps.width, height = sps.height,
            fps = selectedFps, profile = sps.profileIdc,
            level = sps.levelIdc, spsPps = true,
            idr = true
        )
        if (CarPlayMediaPreflight.video(candidate, receiver) !=
            CarPlayMediaPreflight.VideoResult.DIRECT_H264_CANDIDATE_ONLY)
            return Result.PROFILE_OR_SECURITY_MISMATCH
        // A slow, failed or throwing downstream does NOT become a phantom
        // successful video frame, and is never scheduled for later replay.
        if (!runCatching { consumer(encodedAccessUnit, ptsUs) }.getOrDefault(false)) {
            // One dropped compressed AU may be referenced by later predicted
            // frames. Never send P/B AUs until a fresh IDR re-establishes state.
            hasIdr = false
            return Result.BACKPRESSURE_DROP
        }
        hasIdr = true
        previousPts = ptsUs
        return Result.ORIGINAL_COMPRESSED_AU_CONSUMED
    }

    @Synchronized
    fun reset() {
        parsed = null
        negotiated = null
        selectedFps = 0
        previousPts = Long.MIN_VALUE
        hasIdr = false
    }
}
