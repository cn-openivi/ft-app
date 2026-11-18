package app.ft.carplay

/**
 * FT200.5 independent focus gate. Only a physically proven CarPlay ACTIVE
 * session can own projected media/voice focus. A native cellular HFP call
 * always precedes any projected audio. This policy never redirects MIC/PCM.
 */
internal object CarPlayAudioFocusPolicy {
    enum class Result {
        CARPLAY_NOT_ACTIVE, SYSTEM_HFP_CALL, NO_PROJECTION_SOUND,
        PROJECTION_MEDIA, PROJECTION_VOICE
    }

    fun evaluate(
        carPlayPhase: CarPlayHandshakeLedger.Phase,
        situation: CarPlayAudioRolePolicy.Situation
    ): Result {
        if (carPlayPhase != CarPlayHandshakeLedger.Phase.ACTIVE)
            return Result.CARPLAY_NOT_ACTIVE
        if (situation.carrierCallActive)
            return Result.SYSTEM_HFP_CALL
        val choice = CarPlayAudioRolePolicy.evaluate(situation)
        if (choice.owner != CarPlayAudioRolePolicy.Owner.PROJECTION)
            return Result.NO_PROJECTION_SOUND
        return when (choice.primaryRole) {
            CarPlayAudioRolePolicy.Role.MUSIC -> Result.PROJECTION_MEDIA
            CarPlayAudioRolePolicy.Role.SIRI,
            CarPlayAudioRolePolicy.Role.NAVIGATION -> Result.PROJECTION_VOICE
            else -> Result.NO_PROJECTION_SOUND
        }
    }
}
