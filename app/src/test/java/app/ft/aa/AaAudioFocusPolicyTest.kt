package app.ft.aa

import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test

class AaAudioFocusPolicyTest {
    @Test fun mapsAllDocumentedAaAudioRequestTypesToAndroid() {
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, AaAudioFocusPolicy.androidGain(1))
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, AaAudioFocusPolicy.androidGain(2))
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AaAudioFocusPolicy.androidGain(3))
        assertNull(AaAudioFocusPolicy.androidGain(AaProtocol.AUDIO_REQUEST_RELEASE))
        assertNull(AaAudioFocusPolicy.androidGain(999))
    }

    @Test fun androidFocusLossAndDuckHaveCorrectPhoneNotifications() {
        assertEquals(AaProtocol.AUDIO_FOCUS_GAIN,
            AaAudioFocusPolicy.notification(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(AaProtocol.AUDIO_FOCUS_LOSS,
            AaAudioFocusPolicy.notification(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT,
            AaAudioFocusPolicy.notification(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(AaProtocol.AUDIO_FOCUS_LOSS_TRANSIENT_CAN_DUCK,
            AaAudioFocusPolicy.notification(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
    }
}
