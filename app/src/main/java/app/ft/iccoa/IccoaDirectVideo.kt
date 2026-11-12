package app.ft.iccoa

import android.os.Handler
import android.os.SystemClock
import app.ft.aa.AaH264Tap
import app.ft.aa.AaHeadUnitService
import app.ft.aa.AaSession
import app.ft.core.DiagLog
import app.ft.projection.VideoPlan

/**
 * Owner-selected SECOND ICCOA video path. Not a replacement for the FT compositor.
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
    private val onStatus: (String) -> Unit
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
    private var frameAtMs = 0L
    private var activatedAtMs = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (closed || !active) return
            val now = SystemClock.elapsedRealtime()
            if ((firstKeyAtMs == 0L && now - activatedAtMs >= START_TIMEOUT_MS) ||
                (frameAtMs > 0L && now - frameAtMs >= STALL_TIMEOUT_MS)) {
                fallback("No valid AA H.264 frames arrived")
            } else main.postDelayed(this, WATCH_MS)
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
        val supported = IccoaDirectAvc.compatible(
            sps, plan.streamWidth, plan.streamHeight, plan.fps, aaWidth, aaHeight
        )
        val previous = compatibleConfig
        compatibleConfig = if (supported) csd.copyOf() else null
        if (active && (!supported || (previous != null && !previous.contentEquals(csd))))
            main.post { fallback("AA video SPS changed or is unsupported") }
        if (!supported) {
            DiagLog.i("ICCOA", "DIRECT_VIDEO declined: AA AVC SPS incompatible with selected WFD raster")
            onStatus("H.264 direct unavailable for this stream; normal FT video remains active")
        } else {
            DiagLog.i("ICCOA", "DIRECT_VIDEO candidate " + sps!!.width + "x" + sps.height +
                " AVC profile=" + sps.profileIdc + " level=" + sps.levelIdc)
        }
    }

    override fun onFrame(source: AaSession, frame: ByteArray, timestamp: Long): Boolean {
        if (!sourceValid(source) || source !== this.source || failed || frame.isEmpty()) return false
        val csd = compatibleConfig ?: return false
        val key = IccoaDirectAvc.isIdr(frame)
        if (active) {
            // Bounded enqueue never blocks AA AV_MEDIA_ACK on an RTP write.
            val pts = synchronized(clock) { clock.next() }
            output.frame(frame, key, pts)
            frameAtMs = SystemClock.elapsedRealtime()
            if (key) firstKeyAtMs = frameAtMs
            return true
        }
        if (key && !pending) {
            pending = true
            main.post {
                if (!closed && !failed && pending && sourceValid(source) &&
                    source === this.source && compatibleConfig === csd) activate(csd)
                else pending = false
            }
        }
        return false // keep ordinary decoder running until direct path is ready
    }

    private fun activate(csd: ByteArray) {
        pending = false
        if (!projection.beginDirectVideo(plan, aaWidth, aaHeight)) {
            failed = true
            onStatus("Direct AA requires the full-screen Android Auto view")
            return
        }
        output.pause() // discard earlier composited GOP and audio from same RTP muxer
        output.configure(csd)
        synchronized(clock) { clock.reset() }
        firstKeyAtMs = 0L
        frameAtMs = 0L
        activatedAtMs = SystemClock.elapsedRealtime()
        active = true
        output.resume() // now request IDR from AA, not the stopped FT encoder
        onStatus("ICCOA H.264 passthrough active (trial)")
        DiagLog.i("ICCOA", "DIRECT_VIDEO activated, FT SurfaceEncoder stopped")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCH_MS)
    }

    fun requestKeyframe(): Boolean {
        if (!active) return false
        val session = source?.takeIf { sourceValid(it) } ?: return false
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
        if (projection.directVideoActive) {
            output.pause()
            projection.resumeCompositedVideo(plan, output::configure, output::frame)
            if (resumeTransport) output.resume()
        }
        onStatus("ICCOA direct video: " + reason + "; normal FT video restored")
        DiagLog.w("ICCOA", "DIRECT_VIDEO fallback: " + reason)
    }

    fun close() {
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
        private const val STALL_TIMEOUT_MS = 8_000L
    }
}
