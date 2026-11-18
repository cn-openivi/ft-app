package app.ft.carplay

/**
 * FT200.5 source-only audio ownership policy. Never reads or routes carrier
 * downlink PCM: Android's system HFP service remains the real call owner.
 * Projection audio (AirPlay/ICCOA/Baidu) must not double-play an HFP call.
 */
internal object CarPlayAudioRolePolicy {
    enum class Role { PHONE, MUSIC, SIRI, NAVIGATION, RADIO }
    enum class Owner { ANDROID_HFP, PROJECTION, TOYOTA_RADIO, NONE }
    enum class Transport { CARPLAY, ICCOA, BAIDU, NONE }

    data class Situation(
        val transport: Transport,
        val carrierCallActive: Boolean,
        val hfpToSelectedToyota: Boolean,
        val voiceAssistantActive: Boolean,
        val navigationSpeaking: Boolean,
        val projectionMediaPlaying: Boolean,
        val localRadioPlaying: Boolean
    )

    data class Decision(
        val primaryRole: Role?,
        val owner: Owner,
        val allowProjectionMusic: Boolean,
        val allowProjectionMicCapture: Boolean,
        val duckProjectionMusic: Boolean,
        val reason: String
    )

    fun evaluate(situation: Situation): Decision {
        if (situation.carrierCallActive) {
            return Decision(Role.PHONE,
                if (situation.hfpToSelectedToyota) Owner.ANDROID_HFP else Owner.NONE,
                allowProjectionMusic = false, allowProjectionMicCapture = false,
                duckProjectionMusic = true,
                reason = if (situation.hfpToSelectedToyota) "SYSTEM_HFP"
                         else "CARRIER_ROUTE_NOT_VERIFIED")
        }
        if (situation.transport != Transport.NONE && situation.voiceAssistantActive)
            return Decision(Role.SIRI, Owner.PROJECTION, false, false, true, "VOICE_PRIORITY")
        if (situation.transport != Transport.NONE && situation.navigationSpeaking)
            return Decision(Role.NAVIGATION, Owner.PROJECTION, false, false, true, "NAV_PRIORITY")
        if (situation.transport != Transport.NONE && situation.projectionMediaPlaying)
            return Decision(Role.MUSIC, Owner.PROJECTION, true, false, false, "PROJECTION_MEDIA")
        if (situation.localRadioPlaying)
            return Decision(Role.RADIO, Owner.TOYOTA_RADIO, false, false, false, "RADIO_ONLY")
        return Decision(null, Owner.NONE, false, false, false, "NO_ACTIVE_OWNER")
    }
}
