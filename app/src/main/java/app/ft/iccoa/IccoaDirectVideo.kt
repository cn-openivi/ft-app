package app.ft.iccoa

import android.os.Handler
import android.os.SystemClock
import app.ft.aa.AaH264Tap
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaSession
import app.ft.core.DiagLog
import app.ft.projection.VideoPlan

/**
 * Preferred ICCOA video path. Standard composited FT remains available
 * during capability negotiation and is automatically restored on failure.
 * Source: AA compressed AVC. Target: existing ICCOA TS/RTP muxer.
 * All display/transport lifecycle actions run on the Android main Handler.
 */
internal class IccoaDirectVideo(
    private val main: Handler,
    private val output: IccoaMediaTransport,
    private val projection: IccoaProjection,
    private val plan: VideoPlan,
    private val aaWidth: Int,
    private val aaHeight: Int,
    private val onStatus: (String) -> Unit,
    private val onModeChanged: (Boolean, String?) -> Unit
) : AaH264Tap.Listener {
    @Volatile var active: Boolean = false
        private set
    @Volatile private var closed = false
    @Volatile private var failed = false
    @Volatile private var pending = false
    @Volatile private var source: AaSession? = null
    @Volatile private var compatibleConfig: ByteArray? = null
    private val clock = IccoaDirectClock()
    private var firstKeyAtMs = 0L
    private var keyWritesAtActivate = 0L
    private var frameAtMs = 0L
    private var activatedAtMs = 0L
    private var lastKeyRequestMs = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (closed || !active) return
            val now = SystemClock.elapsedRealtime()
            if (firstKeyAtMs == 0L && output.writtenDirectKeyFrames() > keyWritesAtActivate) {
                firstKeyAtMs = now
                DiagLog.i("ICCOA", "DIRECT_VIDEO first AA key frame actually written to RTP")
            }
            when {
                source?.let { !sourceValid(it) } == true ->
                    fallback("Android Auto video session ended")
                firstKeyAtMs == 0L && now - activatedAtMs >= START_TIMEOUT_MS ->
                    fallback("Initial AA H.264 IDR was not written to RTP")
                else -> {
                    // A static AA screen may legally produce no new access units.
                    // Probe a fresh picture without declaring that silence is a crash.
                    // RTSP/RTP transport failure has its own independent close path.
                    if (frameAtMs > 0L && now - frameAtMs >= IDLE_PROBE_MS &&
                        now - lastKeyRequestMs >= IDLE_PROBE_MS) {
                        requestKeyframe()
                        DiagLog.i("ICCOA", "DIRECT_VIDEO idle AA picture; requested fresh IDR")
                    }
                    main.postDelayed(this, WATCH_MS)
                }
            }
        }
    }

    private fun sourceValid(session: AaSession): Boolean =
        !closed && AaHeadUnitService.current === session &&
            session.phase.value == AaSession.Phase.STREAMING

    override fun onStart(source: AaSession) {
        if (closed) return
        if (this.source !== source) compatibleConfig = null
        this.source = source
        pending = false
        if (active) main.post { fallback("Android Auto started a new video session") }
    }

    override fun onConfig(source: AaSession, csd: ByteArray) {
        // Some AA implementations send CSD just before AV_START. Accept the
        // current authenticated source, but never its video frames before STREAMING.
        if (closed || AaHeadUnitService.current !== source || failed) return
        if (this.source != null && this.source !== source) return
        this.source = source
        val sps = IccoaDirectAvc.sps(csd)
        val normalized = IccoaDirectAvc.normalizedConfiguration(csd)
        val rejection = IccoaDirectAvc.compatibilityReason(
            sps, normalized != null,
            plan.streamWidth, plan.streamHeight, plan.fps, aaWidth, aaHeight
        )
        val supported = rejection == null
        val previous = compatibleConfig
        compatibleConfig = if (supported) normalized else null
        if (active && (!supported || (previous != null && !previous.contentEquals(normalized))))
            main.post { fallback("AA video SPS changed or is unsupported") }
        if (!supported) {
            // Fail closed for the entire current car session. A later SPS must
            // not oscillate an already restored normal encoder back to Direct.
            if (!active) { failed = true; pending = false }
            DiagLog.i("ICCOA", "DIRECT_VIDEO declined: ${rejection ?: "unknown incompatibility"}")
            onStatus("H.264 direct unavailable for this stream; normal FT video remains active")
            onModeChanged(false, rejection ?: "AA video does not match WFD")
        } else {
            DiagLog.i("ICCOA", "DIRECT_VIDEO candidate visible=" + sps!!.width + "x" + sps.height +
                " coded=" + sps.codedWidth + "x" + sps.codedHeight +
                " crop=" + sps.cropLeftPx + "," + sps.cropRightPx +
                "," + sps.cropTopPx + "," + sps.cropBottomPx +
                " AVC profile=" + sps.profileIdc + " level=" + sps.levelIdc)
        }
    }

    override fun onFrame(source: AaSession, frame: ByteArray, timestamp: Long): Boolean {
        if (!sourceValid(source) || source !== this.source || failed || frame.isEmpty()) return false
        val csd = compatibleConfig ?: return false
        val key = IccoaDirectAvc.isIdr(frame)
        if (active) {
            // Bounded enqueue never blocks AA AV_MEDIA_ACK on an RTP write.
            val pts = synchronized(clock) { clock.next(timestamp) }
            output.directFrame(frame, key, pts)
            frameAtMs = SystemClock.elapsedRealtime()
            return true
        }
        if (key && !pending) {
            // Keep an owned, MAX 4 MiB, independent copy of the FIRST IDR.
            // The AA callback buffer is NOT owned by the main Handler. Without
            // this handoff FT185 stopped its encoder and discarded the only key.
            val retained = IccoaFirstIdrHandoff.capture(frame, timestamp, csd)
                ?: return false
            pending = true
            val posted = main.post {
                if (!closed && !failed && pending && sourceValid(source) &&
                    source === this.source &&
                    IccoaFirstIdrHandoff.configurationStillCurrent(retained, compatibleConfig))
                    activate(retained)
                else pending = false
            }
            if (!posted) pending = false
        }
        return false // Decode normally while switching; no AA reader stalls
    }

    private fun activate(retained: IccoaFirstIdrHandoff.Frame) {
        pending = false
        if (!projection.beginDirectVideo(plan, aaWidth, aaHeight)) {
            failed = true
            onStatus("Direct AA requires the full-screen Android Auto view")
            onModeChanged(false, "Android Auto is not full-screen")
            return
        }
        output.pause() // discard composited GOP and AAC from previous RTP epoch
        output.selectDirectFrames(true)
        output.configure(retained.config)
        synchronized(clock) { clock.reset() }
        firstKeyAtMs = 0L
        keyWritesAtActivate = output.writtenDirectKeyFrames()
        frameAtMs = 0L
        activatedAtMs = SystemClock.elapsedRealtime()
        // Do not request a SECOND AA IDR before the retained first one is in
        // the queue: otherwise the two keyframes could arrive out of order.
        output.resume(requestNewKeyframe = false)
        val pts = synchronized(clock) { clock.next(retained.timestamp) }
        output.directFrame(retained.bytes, true, pts)
        frameAtMs = SystemClock.elapsedRealtime()
        active = true
        onModeChanged(true, null)
        onStatus("ICCOA H.264 direct: first IDR queued, RTP verification pending")
        DiagLog.i("ICCOA", "DIRECT_VIDEO first IDR retained and queued; awaiting RTP key-write proof")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCH_MS)
    }

    fun requestKeyframe(): Boolean {
        if (!active) return false
        val session = source?.takeIf { sourceValid(it) } ?: return false
        val now = SystemClock.elapsedRealtime()
        // A backed-up RTP queue must not flood the AA control lane with VIDEO_FOCUS.
        if (lastKeyRequestMs > 0 && now - lastKeyRequestMs < KEY_REQUEST_COOLDOWN_MS) return true
        lastKeyRequestMs = now
        session.askForAFreshPicture()
        return true
    }

    override fun onStop(source: AaSession) {
        if (source !== this.source || closed) return
        compatibleConfig = null
        main.post { fallback("Android Auto video stopped") }
    }

    /** A new WFD mode is never fed the previous AA bitstream. */
    fun videoPlanChanged(next: VideoPlan) {
        if (next != plan && !closed && !failed) fallback("Receiver changed WFD video format")
    }

    fun paused() {
        if (active || pending) fallback("Receiver paused ICCOA video", resumeTransport = false)
    }

    fun disconnected() {
        if (active || pending) fallback("Android Auto disconnected")
    }

    /** Phone UI emergency recovery: immediately stop direct mode in this car session. */
    fun restoreNormalByUser() {
        if (active || pending) fallback("Selected normal video on the phone")
        else failed = true
    }

    private fun fallback(reason: String, resumeTransport: Boolean = true) {
        if (closed || failed) return
        failed = true // no re-entry until the next ICCOA connection
        pending = false
        active = false
        main.removeCallbacks(watchdog)
        val beforeRecovery = output.writtenCompositedFrames()
        if (projection.directVideoActive) {
            output.pause()
            output.selectDirectFrames(false)
            projection.resumeCompositedVideo(plan, output::configure, output::frame)
            if (resumeTransport) output.resume()
        }
        if (resumeTransport) watchCompositedRecovery(beforeRecovery)
        onModeChanged(false, reason)
        onStatus("ICCOA direct video: " + reason + "; normal FT video restarting; RTP check pending")
        DiagLog.w("ICCOA", "DIRECT_VIDEO fallback: " + reason)
    }

    /** Verify observable RTP video after fallback; retry only FT's own IDR request. */
    private fun watchCompositedRecovery(baseline: Long) {
        main.postDelayed({
            if (!closed && failed && !projection.directVideoActive) {
                if (output.writtenCompositedFrames() > baseline) {
                    DiagLog.i("ICCOA", "COMPOSITED_RECOVERY_RTP_VIDEO_WRITTEN")
                    onStatus("Normal FT video: composited RTP frames written")
                } else {
                    DiagLog.w("ICCOA", "COMPOSITED_RECOVERY_NO_RTP_VIDEO_3S; requesting FT IDR")
                    projection.requestKeyFrame()
                    main.postDelayed({
                        if (!closed && failed && !projection.directVideoActive) {
                            if (output.writtenCompositedFrames() > baseline)
                                DiagLog.i("ICCOA", "COMPOSITED_RECOVERY_RTP_VIDEO_WRITTEN_AFTER_IDR")
                            else DiagLog.w("ICCOA", "COMPOSITED_RECOVERY_NO_RTP_VIDEO_6S")
                        }
                    }, 3_000L)
                }
            }
        }, 3_000L)
    }

    fun close() {
        output.selectDirectFrames(false)
        closed = true
        failed = true
        active = false
        pending = false
        main.removeCallbacks(watchdog)
        if (AaH264Tap.listener === this) AaH264Tap.listener = null
        compatibleConfig = null
        source = null
    }

    companion object {
        private const val WATCH_MS = 1_000L
        private const val START_TIMEOUT_MS = 5_000L
        private const val IDLE_PROBE_MS = 30_000L
        private const val KEY_REQUEST_COOLDOWN_MS = 2_500L
    }
}
