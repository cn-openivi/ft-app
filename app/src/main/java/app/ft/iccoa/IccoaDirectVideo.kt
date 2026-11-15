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
    // Never restart FT's encoder on a transient Toyota PAUSE.
    private var awaitingResumeRtpIdr = false
    private var resumeIdrReceived = false
    private var directNoIdrWarned = false
    private var gopStallWarned = false
    @Volatile private var rearmPending = false
    @Volatile private var restartGraceAtMs = 0L
    @Volatile private var receiverPaused = false
    private var observedGopDrops = 0L
    private var gopLostAtMs = 0L
    private var directKeyWritesAtLoss = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (closed || !active) return
            val now = SystemClock.elapsedRealtime()
            if (receiverPaused) {
                // OEM CONTROL5 / RTSP PAUSE is an ordinary transport suspension.
                // Keep the AA bitstream route; PLAY must re-seed with a new IDR.
                main.postDelayed(this, WATCH_MS)
                return
            }
            val drops = output.directGopDropCount()
            if (drops > observedGopDrops) {
                observedGopDrops = drops
                gopLostAtMs = now
                directKeyWritesAtLoss = output.directKeyWritesAtGopLoss()
                DiagLog.w("ICCOA", "DIRECT_VIDEO_GOP_WAIT generation=$drops " +
                    "baselineKeyWrites=$directKeyWritesAtLoss")
            }
            if (gopLostAtMs > 0L && output.writtenDirectKeyFrames() > directKeyWritesAtLoss) {
                DiagLog.i("ICCOA", "DIRECT_VIDEO_GOP_RECOVERED elapsedMs=${now - gopLostAtMs}")
                gopLostAtMs = 0L
                gopStallWarned = false
            }
            if (firstKeyAtMs == 0L && output.writtenDirectKeyFrames() > keyWritesAtActivate) {
                firstKeyAtMs = now
                if (awaitingResumeRtpIdr) {
                    DiagLog.i("ICCOA", "DIRECT_VIDEO_POST_PAUSE_RECOVERED " +
                        "elapsedMs=${now - activatedAtMs} idrReceived=$resumeIdrReceived " +
                        "rtpKeyWrites=${output.writtenDirectKeyFrames()}")
                    awaitingResumeRtpIdr = false
                    resumeIdrReceived = false
                } else {
                    DiagLog.i("ICCOA", "DIRECT_VIDEO first AA key frame actually written to RTP")
                }
            }
            if (source?.let { !sourceValid(it) } == true) {
                // Temporary AV_STOP is often followed by AV_START. Do not
                // turn it into a second encoder just because the watchdog
                // observed a non-STREAMING AA phase inside the grace window.
                if (rearmPending && restartGraceAtMs > 0L &&
                    now - restartGraceAtMs < AA_RESTART_GRACE_MS) {
                    main.postDelayed(this, WATCH_MS)
                    return
                }
                fallback("Android Auto video session ended; native FT UI required")
                return
            }
            // While the original AA compressed stream is still available,
            // missing IDR / RTP GOP is a recovery problem, NOT permission to
            // decode AA and start FT's second AVC encoder.
            if (firstKeyAtMs == 0L && awaitingResumeRtpIdr &&
                IccoaDirectRecoveryPolicy.postPauseExpired(now - activatedAtMs) &&
                !directNoIdrWarned) {
                directNoIdrWarned = true
                DiagLog.w("ICCOA", "DIRECT_VIDEO_POST_PAUSE_STALLED " +
                    "elapsedMs=${now - activatedAtMs} idrReceived=$resumeIdrReceived " +
                    "AA_source_still_active=true FT_encoder_remains_off=true")
                onStatus("AA direct waiting for fresh H.264 IDR; no FT re-encode")
            }
            if (firstKeyAtMs == 0L && !awaitingResumeRtpIdr &&
                now - activatedAtMs >= START_TIMEOUT_MS && !directNoIdrWarned) {
                directNoIdrWarned = true
                DiagLog.w("ICCOA", "DIRECT_VIDEO_FIRST_IDR_STALLED " +
                    "elapsedMs=${now - activatedAtMs} FT_encoder_remains_off=true")
                onStatus("AA direct awaiting original IDR; no FT re-encode")
            }
            if (gopLostAtMs > 0L &&
                now - gopLostAtMs >= GOP_RECOVERY_TIMEOUT_MS && !gopStallWarned) {
                gopStallWarned = true
                DiagLog.w("ICCOA", "DIRECT_VIDEO_GOP_STALLED " +
                    "elapsedMs=${now - gopLostAtMs} FT_encoder_remains_off=true")
                onStatus("AA direct recovering RTP GOP; no FT re-encode")
            }
            // Bounded control request *frequency*, without a terminal timeout
            // that would permanently lock this car session into compositing.
            if (firstKeyAtMs == 0L &&
                IccoaDirectRecoveryPolicy.shouldRetryPostPause(
                    now - activatedAtMs, now - lastKeyRequestMs)) {
                if (requestKeyframe() && awaitingResumeRtpIdr)
                    DiagLog.i("ICCOA", "DIRECT_VIDEO_POST_PAUSE_IDR_RETRY " +
                        "elapsedMs=${now - activatedAtMs} idrReceived=$resumeIdrReceived")
            }
            if (gopLostAtMs > 0L && now - gopLostAtMs >= GOP_RETRY_AFTER_MS &&
                now - lastKeyRequestMs >= KEY_REQUEST_COOLDOWN_MS)
                requestKeyframe()
            // A static AA screen may legally produce no new access units.
            if (frameAtMs > 0L && now - frameAtMs >= IDLE_PROBE_MS &&
                now - lastKeyRequestMs >= IDLE_PROBE_MS) {
                requestKeyframe()
                DiagLog.i("ICCOA", "DIRECT_VIDEO idle AA picture; requested fresh IDR")
            }
            main.postDelayed(this, WATCH_MS)
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
        restartGraceAtMs = 0L
        if (active) {
            rearmPending = true
            main.post {
                if (!closed && active && this.source === source)
                    rearmDirectGop("AA_VIDEO_SESSION_RESTART")
            }
        }
    }

    /**
     * New AA START / compatible SPS is a new *compressed* GOP, not a reason to
     * decode and re-encode AA pixels. Keep the same RTP sender and request a
     * new original IDR. This is a real RTP epoch boundary, never a P-frame
     * resumption from a previous encoder session.
     */
    private fun rearmDirectGop(reason: String) {
        if (closed || !active) return
        rearmPending = true
        output.pause()
        output.selectDirectFrames(true)
        compatibleConfig?.let(output::configure)
        synchronized(clock) { clock.reset() }
        observedGopDrops = output.directGopDropCount()
        gopLostAtMs = 0L
        firstKeyAtMs = 0L
        frameAtMs = 0L
        keyWritesAtActivate = output.writtenDirectKeyFrames()
        activatedAtMs = SystemClock.elapsedRealtime()
        lastKeyRequestMs = 0L
        awaitingResumeRtpIdr = true
        resumeIdrReceived = false
        directNoIdrWarned = false
        gopStallWarned = false
        if (!receiverPaused) output.resume(requestNewKeyframe = false)
        rearmPending = false
        if (!receiverPaused) requestKeyframe()
        DiagLog.i("ICCOA", "DIRECT_VIDEO_GOP_REARMED reason=$reason " +
            "configured=${compatibleConfig != null} doubleEncode=false awaiting_RTP_IDR=true")
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
        if (active && !supported) {
            // Incompatible SPS is a demonstrated codec/canvas mismatch. This
            // is the exception where composition can actually be necessary.
            main.post { if (!closed && active) fallback("AA SPS incompatible with receiver") }
        } else if (active && supported && normalized != null) {
            if (previous != null && !previous.contentEquals(normalized)) {
                rearmPending = true
                main.post {
                    if (!closed && active && this.source === source &&
                        compatibleConfig?.contentEquals(normalized) == true)
                        rearmDirectGop("COMPATIBLE_AA_SPS_CHANGED")
                }
            } else if (previous == null) {
                // New AA source has supplied its first compatible codec config.
                main.post {
                    if (!closed && active && this.source === source &&
                        compatibleConfig?.contentEquals(normalized) == true) {
                        output.configure(normalized)
                        requestKeyframe()
                        DiagLog.i("ICCOA", "DIRECT_VIDEO_NEW_AA_CONFIG no_reencode=true")
                    }
                }
            }
        }
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
        if (receiverPaused || rearmPending) return active // Ignore old or not-yet-rearmed GOP.
        val csd = compatibleConfig ?: return active // Direct pending new AA SPS; do not decode again.
        val key = IccoaDirectAvc.isIdr(frame)
        if (active) {
            if (awaitingResumeRtpIdr && key && !resumeIdrReceived) {
                resumeIdrReceived = true
                DiagLog.i("ICCOA", "DIRECT_VIDEO_POST_PAUSE_IDR_RECEIVED awaiting_RTP=true")
            }
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
        observedGopDrops = output.directGopDropCount()
        gopLostAtMs = 0L
        receiverPaused = false
        rearmPending = false
        restartGraceAtMs = 0L
        awaitingResumeRtpIdr = false
        resumeIdrReceived = false
        directNoIdrWarned = false
        gopStallWarned = false
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
        DiagLog.i("ICCOA", "DIRECT_VIDEO_IDR_REQUESTED mode=AA_VIDEO_FOCUS")
        return true
    }

    override fun onStop(source: AaSession) {
        if (source !== this.source || closed) return
        compatibleConfig = null
        // AV_STOP often precedes a new AV_START during surface changes. Wait
        // briefly for a real restart before showing the native FT compositor.
        if (active) {
            rearmPending = true
            restartGraceAtMs = SystemClock.elapsedRealtime()
            DiagLog.i("ICCOA", "DIRECT_VIDEO_AA_STOP_RESTART_GRACE no_reencode=true")
            main.postDelayed({
                if (!closed && active && this.source === source &&
                    source.phase.value != AaSession.Phase.STREAMING)
                    fallback("Android Auto stream ended; return to native FT home")
            }, AA_RESTART_GRACE_MS)
        } else main.post { fallback("Android Auto video stopped") }
    }

    /** A new WFD mode is never fed the previous AA bitstream. */
    fun videoPlanChanged(next: VideoPlan) {
        if (next != plan && !closed && !failed) fallback("Receiver changed WFD video format")
    }

    /** The OEM may PAUSE for only a few seconds while changing its surface. */
    fun paused() {
        if (!active && !pending) return
        pending = false
        receiverPaused = true
        DiagLog.i("ICCOA", "DIRECT_VIDEO_RECEIVER_PAUSED route_retained=true")
    }

    /** Call immediately before WFD PLAY resumes the RTP queue. */
    fun resumed() {
        if (!receiverPaused) return
        receiverPaused = false
        if (active) {
            // A PAUSE clears RTP dependencies: request an IDR, never resume on P frames.
            synchronized(clock) { clock.reset() }
            observedGopDrops = output.directGopDropCount()
            gopLostAtMs = 0L
            keyWritesAtActivate = output.writtenDirectKeyFrames()
            firstKeyAtMs = 0L
            frameAtMs = 0L
            awaitingResumeRtpIdr = true
            resumeIdrReceived = false
            directNoIdrWarned = false
            gopStallWarned = false
            activatedAtMs = SystemClock.elapsedRealtime()
            lastKeyRequestMs = 0L
            DiagLog.i("ICCOA", "DIRECT_VIDEO_RECEIVER_RESUMED awaiting_new_RTP_IDR=true " +
                "firstWarningMs=${IccoaDirectRecoveryPolicy.POST_PAUSE_MAX_MS} " +
                "autoReencode_on_IDR_timeout=false FT_encoder_stays_off=true")
        }
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
        receiverPaused = false
        awaitingResumeRtpIdr = false
        resumeIdrReceived = false
        gopLostAtMs = 0L
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
        receiverPaused = false
        awaitingResumeRtpIdr = false
        resumeIdrReceived = false
        gopLostAtMs = 0L
        main.removeCallbacks(watchdog)
        if (AaH264Tap.listener === this) AaH264Tap.listener = null
        compatibleConfig = null
        source = null
    }

    companion object {
        private const val WATCH_MS = 1_000L
        private const val AA_RESTART_GRACE_MS = 3_000L
        private const val START_TIMEOUT_MS = 5_000L
        private const val IDLE_PROBE_MS = 30_000L
        private const val GOP_RETRY_AFTER_MS = 2_500L
        private const val GOP_RECOVERY_TIMEOUT_MS = 6_000L
        private const val KEY_REQUEST_COOLDOWN_MS = 2_500L
    }
}
