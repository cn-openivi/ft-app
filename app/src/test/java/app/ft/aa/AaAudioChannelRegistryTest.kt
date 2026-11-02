package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaAudioChannelRegistryTest {
    private val lanes = AaAudioChannelRegistry()

    @Test fun startedSpeechMustBeEndedOnChannelCloseWithoutAvStop() {
        assertTrue(lanes.start(AaProtocol.CH_SPEECH_AUDIO, 41))
        assertEquals(41, lanes.session(AaProtocol.CH_SPEECH_AUDIO))
        assertTrue(lanes.stop(AaProtocol.CH_SPEECH_AUDIO))
        assertFalse(lanes.stop(AaProtocol.CH_SPEECH_AUDIO))
        assertEquals(0, lanes.session(AaProtocol.CH_SPEECH_AUDIO))
    }

    @Test fun mediaSpeechAndSystemEndExactlyOnceAtSessionShutdown() {
        lanes.start(AaProtocol.CH_MEDIA_AUDIO, 12)
        lanes.start(AaProtocol.CH_SPEECH_AUDIO, 13)
        lanes.start(AaProtocol.CH_SYSTEM_AUDIO, 14)
        assertEquals(setOf(4, 5, 6), lanes.stopAll())
        assertTrue(lanes.stopAll().isEmpty())
        assertFalse(lanes.stop(AaProtocol.CH_SPEECH_AUDIO))
    }

    @Test fun restartingSameAudioChannelUpdatesAckSessionWithoutDoubleBegin() {
        assertTrue(lanes.start(AaProtocol.CH_SPEECH_AUDIO, 11))
        assertFalse(lanes.start(AaProtocol.CH_SPEECH_AUDIO, 19))
        assertEquals(19, lanes.session(AaProtocol.CH_SPEECH_AUDIO))
        assertTrue(lanes.stop(AaProtocol.CH_SPEECH_AUDIO))
    }

    @Test fun channelMayReopenCleanlyAfterClose() {
        lanes.start(AaProtocol.CH_SPEECH_AUDIO, 15)
        assertTrue(lanes.stop(AaProtocol.CH_SPEECH_AUDIO))
        assertTrue(lanes.start(AaProtocol.CH_SPEECH_AUDIO, 16))
        assertEquals(16, lanes.session(AaProtocol.CH_SPEECH_AUDIO))
    }

    @Test fun separateLanesDoNotClearEachOther() {
        lanes.start(AaProtocol.CH_MEDIA_AUDIO, 7)
        lanes.start(AaProtocol.CH_SPEECH_AUDIO, 8)
        assertTrue(lanes.stop(AaProtocol.CH_SPEECH_AUDIO))
        assertEquals(7, lanes.session(AaProtocol.CH_MEDIA_AUDIO))
        assertEquals(setOf(AaProtocol.CH_MEDIA_AUDIO), lanes.stopAll())
    }
}
