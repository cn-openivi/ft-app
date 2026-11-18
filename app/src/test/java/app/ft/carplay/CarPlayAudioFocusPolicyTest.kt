package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayAudioFocusPolicyTest {
    private val policy = CarPlayAudioFocusPolicy
    private fun situation(
        call: Boolean = false, siri: Boolean = false, nav: Boolean = false,
        media: Boolean = true,
        transport: CarPlayAudioRolePolicy.Transport = CarPlayAudioRolePolicy.Transport.CARPLAY
    ) = CarPlayAudioRolePolicy.Situation(
        transport, call, true, siri, nav, media, false
    )

    @Test fun noSourceOnlyLedgerClaimCanOwnRealAndroidMediaFocus() {
        for (phase in CarPlayHandshakeLedger.Phase.entries) {
            if (phase == CarPlayHandshakeLedger.Phase.ACTIVE) continue
            assertEquals(CarPlayAudioFocusPolicy.Result.CARPLAY_NOT_ACTIVE,
                policy.evaluate(phase,situation()))
        }
    }

    @Test fun realHuActiveStillLosesMusicWhenSystemCarrierCallStarts() {
        assertEquals(CarPlayAudioFocusPolicy.Result.PROJECTION_MEDIA,
            policy.evaluate(CarPlayHandshakeLedger.Phase.ACTIVE,situation()))
        assertEquals(CarPlayAudioFocusPolicy.Result.SYSTEM_HFP_CALL,
            policy.evaluate(CarPlayHandshakeLedger.Phase.ACTIVE,situation(call = true)))
    }

    @Test fun navigationAndVoiceRequireTransientAudioNotPhonePcm() {
        assertEquals(CarPlayAudioFocusPolicy.Result.PROJECTION_VOICE,
            policy.evaluate(CarPlayHandshakeLedger.Phase.ACTIVE,situation(siri = true)))
        assertEquals(CarPlayAudioFocusPolicy.Result.PROJECTION_VOICE,
            policy.evaluate(CarPlayHandshakeLedger.Phase.ACTIVE,situation(nav = true)))
    }

    @Test fun staleCarPlayHandshakeNeverStealsBaiduOrIccoaFocus() {
        for (other in listOf(CarPlayAudioRolePolicy.Transport.BAIDU,
                CarPlayAudioRolePolicy.Transport.ICCOA)) {
            assertEquals(CarPlayAudioFocusPolicy.Result.NO_PROJECTION_SOUND,
                policy.evaluate(CarPlayHandshakeLedger.Phase.ACTIVE,
                    situation(transport = other)))
        }
    }

    @Test fun noCarPlayTransportNoProjectionFocus() {
        assertEquals(CarPlayAudioFocusPolicy.Result.NO_PROJECTION_SOUND,
            policy.evaluate(CarPlayHandshakeLedger.Phase.ACTIVE,
                situation(transport = CarPlayAudioRolePolicy.Transport.NONE,media = false)))
    }
}
