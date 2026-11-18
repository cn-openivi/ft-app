package app.ft.aa

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import app.ft.core.DiagLog

/** AA request values follow the phone's AudioFocusType (1/2/3/4).
 * Android 15+ requires visible app or real FGS before requesting focus.
 */
internal object AaAudioFocusPolicy {
    fun androidGain(request: Int): Int? = when (request) {
        1 -> AudioManager.AUDIOFOCUS_GAIN
        2 -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        3 -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        else -> null
    }

    fun notification(change: Int): Int = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> AaProtocol.AUDIO_FOCUS_GAIN
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT_CAN_DUCK
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT
        else -> AaProtocol.AUDIO_FOCUS_LOSS
    }
}

/** Holds only system AudioManager focus, not AA playback or telephony audio.
 * Each AA session owns and releases its request. HFP calls retain priority.
 */
internal class AaSystemAudioFocus(
    context: Context,
    private val report: (Int) -> Unit
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var active: AudioFocusRequest? = null
    @Volatile private var closed = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (!closed) report(AaAudioFocusPolicy.notification(change))
    }

    @Synchronized fun request(type: Int): Int {
        if (closed || manager == null) return AaProtocol.AUDIO_FOCUS_LOSS
        if (type == AaProtocol.AUDIO_REQUEST_RELEASE) {
            abandon()
            return AaProtocol.AUDIO_FOCUS_LOSS
        }
        val gain = AaAudioFocusPolicy.androidGain(type) ?: return AaProtocol.AUDIO_FOCUS_LOSS
        val phoneCall = manager.mode == AudioManager.MODE_IN_CALL ||
            manager.mode == AudioManager.MODE_IN_COMMUNICATION
        if (phoneCall) {
            abandon()
            DiagLog.i("AA", "AUDIO_FOCUS_DEFERRED phone-call-priority=true")
            return AaProtocol.AUDIO_FOCUS_LOSS
        }
        abandon()
        val attrs = AudioAttributes.Builder()
            .setUsage(if (type == 3) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_MEDIA)
            .setContentType(if (type == 3) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener)
            .build()
        val result = runCatching { manager.requestAudioFocus(request) }
            .onFailure { DiagLog.w("AA", "AUDIO_FOCUS_SYSTEM_DENIED ${it.javaClass.simpleName}") }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            DiagLog.i("AA", "AUDIO_FOCUS_NOT_GRANTED result=$result request=$type")
            return AaProtocol.AUDIO_FOCUS_LOSS
        }
        active = request
        return if (type == 2) AaProtocol.AUDIO_FOCUS_GAIN_TRANSIENT
            else AaProtocol.AUDIO_FOCUS_GAIN
    }

    @Synchronized private fun abandon() {
        active?.let { runCatching { manager?.abandonAudioFocusRequest(it) } }
        active = null
    }

    @Synchronized fun close() {
        closed = true
        abandon()
    }
}
