package app.ft.carplay

/**
 * FT200.5: reject delayed Android AudioFocus callbacks from abandoned or
 * superseded requests. This protects later native HFP calls and other
 * projection protocols from stale LOSS/GAIN after a source switch.
 *
 * Pure synchronization helper; never intercepts telephony, PCM or microphone.
 */
internal class CarPlayAudioFocusGenerationFence {
    private var activeGeneration = 0L

    @Synchronized fun beginRequest(): Long = ++activeGeneration

    @Synchronized fun invalidate() {
        activeGeneration++
    }

    @Synchronized fun accept(
        generation: Long,
        state: Int,
        consume: (Int) -> Unit
    ): Boolean {
        if (generation <= 0L || generation != activeGeneration) return false
        // Deliver under the same lock used by invalidate; closing a request
        // cannot race a stale GAIN to overwrite the new source's state.
        consume(state)
        return true
    }
}
