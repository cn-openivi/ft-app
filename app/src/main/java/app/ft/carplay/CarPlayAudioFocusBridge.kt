package app.ft.carplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Real Android AudioFocusRequest/abandon handling for FUTURE CarPlay lane.
 * System HFP owns operator calls. No AudioRecord/Telecom interception or
 * projection mixing under a carrier call. Not connected to FT190 sinks.
 */
internal class CarPlayAudioFocusBridge(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var activeType: Int? = null
    private val focusGeneration = CarPlayAudioFocusGenerationFence()
    @Volatile var currentFocus: Int = AudioManager.AUDIOFOCUS_LOSS
        private set

    enum class Status {
        NATIVE_CALL_PRIORITY, CARPLAY_UNAVAILABLE, NO_AUDIO,
        FOCUS_GRANTED, FOCUS_DENIED, AUDIO_MANAGER_UNAVAILABLE
    }

    @Synchronized
    fun update(
        realCarPlayPhase: CarPlayHandshakeLedger.Phase,
        situation: CarPlayAudioRolePolicy.Situation
    ): Status {
        val audio = manager ?: return Status.AUDIO_MANAGER_UNAVAILABLE
        // Android's own call mode remains authoritative even if our app's
        // Telephony observer cannot read privileged cellular state.
        if (situation.carrierCallActive || audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
            close()
            return Status.NATIVE_CALL_PRIORITY
        }
        return when (CarPlayAudioFocusPolicy.evaluate(realCarPlayPhase, situation)) {
            CarPlayAudioFocusPolicy.Result.CARPLAY_NOT_ACTIVE -> {
                close(); Status.CARPLAY_UNAVAILABLE
            }
            CarPlayAudioFocusPolicy.Result.NO_PROJECTION_SOUND,
            CarPlayAudioFocusPolicy.Result.SYSTEM_HFP_CALL -> {
                close(); Status.NO_AUDIO
            }
            else -> {
                val voice = CarPlayAudioFocusPolicy.evaluate(realCarPlayPhase, situation) ==
                    CarPlayAudioFocusPolicy.Result.PROJECTION_VOICE
                val gain = if (voice) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    else AudioManager.AUDIOFOCUS_GAIN
                if (request != null && activeType == gain &&
                    currentFocus == AudioManager.AUDIOFOCUS_GAIN)
                    return Status.FOCUS_GRANTED
                close()
                val attrs = AudioAttributes.Builder()
                    .setContentType(if (voice) AudioAttributes.CONTENT_TYPE_SPEECH
                        else AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(if (voice) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                        else AudioAttributes.USAGE_MEDIA)
                    .build()
                val generation = focusGeneration.beginRequest()
                val listener = AudioManager.OnAudioFocusChangeListener { next ->
                    focusGeneration.accept(generation, next) { currentFocus = it }
                }
                val fresh = AudioFocusRequest.Builder(gain)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                val accepted = runCatching { audio.requestAudioFocus(fresh) }
                    .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
                if (accepted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    request = fresh
                    activeType = gain
                    currentFocus = AudioManager.AUDIOFOCUS_GAIN
                    Status.FOCUS_GRANTED
                } else {
                    focusGeneration.invalidate()
                    currentFocus = AudioManager.AUDIOFOCUS_LOSS
                    Status.FOCUS_DENIED
                }
            }
        }
    }

    @Synchronized override fun close() {
        focusGeneration.invalidate()
        val req = request
        request = null
        activeType = null
        currentFocus = AudioManager.AUDIOFOCUS_LOSS
        if (req != null) runCatching { manager?.abandonAudioFocusRequest(req) }
    }
}
