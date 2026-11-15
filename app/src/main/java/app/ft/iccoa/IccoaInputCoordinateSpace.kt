package app.ft.iccoa

import android.content.Context
import app.ft.projection.IccoaCalibrationScope
import app.ft.projection.IccoaWelcomeTarget
import app.ft.projection.TouchVideoGeometry
import app.ft.projection.VideoPlan

/**
 * UIBC packets can omit coordinate width/height. A receiver may then send
 * native display pixels OR encoded WFD stream pixels. The two spaces differ
 * when a wide panel displays a 16:9 H.264 frame.
 *
 * Detect only when a single interpretation unambiguously hits the center
 * target selected by the user. No make/model assumptions, no free calibration
 * points, and no guessed fallback for unrelated touches.
 */
internal object IccoaInputCoordinateSpace {
    enum class Mode { UNKNOWN, NATIVE_DISPLAY, VIDEO_STREAM }

    // This is the meaning of UIBC coordinates for one VERIFIED receiver,
    // not a vehicle/phone model profile or a calibration transform.
    private const val PROFILE_PREF = "ft-iccoa-uibc-domain"
    private const val PROFILE_KEY = "uibc-domain-v187"

    fun parseSaved(value: String?): Mode = when (value) {
        Mode.NATIVE_DISPLAY.name -> Mode.NATIVE_DISPLAY
        Mode.VIDEO_STREAM.name -> Mode.VIDEO_STREAM
        else -> Mode.UNKNOWN
    }

    fun requiresWelcome(previouslyVerified: Boolean, knownMode: Mode): Boolean =
        !previouslyVerified || knownMode == Mode.UNKNOWN

    /** Never use a model name, BLE MAC, or unverified identity as the key. */
    fun remembered(context: Context): Mode {
        val key = IccoaCalibrationScope.key(PROFILE_KEY) ?: return Mode.UNKNOWN
        return parseSaved(context.getSharedPreferences(PROFILE_PREF, Context.MODE_PRIVATE)
            .getString(key, null))
    }

    /** Save only a uniquely selected interpretation of the owner-pressed OK. */
    fun rememberVerified(context: Context, mode: Mode): Boolean {
        if (mode == Mode.UNKNOWN) return false
        val key = IccoaCalibrationScope.key(PROFILE_KEY) ?: return false
        return context.getSharedPreferences(PROFILE_PREF, Context.MODE_PRIVATE)
            .edit().putString(key, mode.name).commit()
    }

    fun forgetReceiver(context: Context, verifiedWelcomeKey: String): Boolean {
        if (!Regex("^verified_[0-9a-f]{64}:welcome$").matches(verifiedWelcomeKey)) return false
        val key = verifiedWelcomeKey.removeSuffix("welcome") + PROFILE_KEY
        return context.getSharedPreferences(PROFILE_PREF, Context.MODE_PRIVATE)
            .edit().remove(key).commit()
    }

    /**
     * OEM Carlink b3::sendTouchEvent sends JSON width/height from the local
     * SurfaceView dimensions, but x0/y0 AFTER b3::a/b has scaled them to
     * the remote SOURCE video size (possibly also applying rotation and a
     * visible-region crop). The field pair is therefore NOT guaranteed to
     * describe the numeric range of x0/y0.
     *
     * Other generic UIBC peers may send untransformed local pixels instead.
     * Use the deliberately known CENTER OK sample to choose between exactly
     * these two lawful interpretations, independent of car/phone make.
     * A mismatch under both models remains UNKNOWN and cannot become PASS.
     */
    fun inferRawCenter(
        rawX: Double,
        rawY: Double,
        declaredWidth: Int,
        declaredHeight: Int,
        frame: app.ft.projection.VideoContentTransform
    ): Mode {
        if (declaredWidth <= 0 || declaredHeight <= 0 ||
            !rawX.isFinite() || !rawY.isFinite()) return Mode.UNKNOWN

        val native = UibcFrameMapping.toStream(
            rawX, rawY, declaredWidth, declaredHeight, frame)
        val preMapped = UibcFrameMapping.fromStream(
            rawX, rawY, frame.streamWidth, frame.streamHeight)

        fun hits(point: Pair<Int, Int>?): Boolean {
            if (point == null || !frame.isStreamContent(
                    point.first.toDouble(), point.second.toDouble())) return false
            val (x, y) = frame.streamToContent(
                point.first.toDouble(), point.second.toDouble())
            return IccoaWelcomeTarget.contains(
                x.toInt(), y.toInt(), frame.contentWidth, frame.contentHeight)
        }
        return when {
            hits(native) && !hits(preMapped) -> Mode.NATIVE_DISPLAY
            hits(preMapped) && !hits(native) -> Mode.VIDEO_STREAM
            // Same transforms / dimensions, no unique proof but equivalent.
            hits(native) && hits(preMapped) && native == preMapped -> Mode.NATIVE_DISPLAY
            else -> Mode.UNKNOWN
        }
    }

    fun inferFromWelcome(nativeRawX: Int, nativeRawY: Int, plan: VideoPlan): Mode {
        val geometry = TouchVideoGeometry(
            plan.contentWidth, plan.contentHeight, plan.streamWidth,
            plan.streamHeight, plan.preserveAspect
        )
        val native = geometry.toStream(
            nativeRawX.coerceIn(0, plan.contentWidth - 1),
            nativeRawY.coerceIn(0, plan.contentHeight - 1)
        )
        val stream = nativeRawX.coerceIn(0, plan.streamWidth - 1) to
            nativeRawY.coerceIn(0, plan.streamHeight - 1)
        fun hits(point: Pair<Int, Int>): Boolean {
            val (x, y) = geometry.toContent(point.first, point.second)
            return IccoaWelcomeTarget.contains(x, y, plan.contentWidth, plan.contentHeight)
        }
        return when {
            hits(native) && !hits(stream) -> Mode.NATIVE_DISPLAY
            hits(stream) && !hits(native) -> Mode.VIDEO_STREAM
            else -> Mode.UNKNOWN
        }
    }
}
