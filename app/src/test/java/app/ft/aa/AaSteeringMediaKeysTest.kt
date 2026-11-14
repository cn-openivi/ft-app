package app.ft.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AaSteeringMediaKeysTest {
    @Test fun carLifeWheelCodesMapWithoutSwappingStandardAndroidKeys() {
        assertEquals(88, AaSteeringMediaKeys.normalize(15, false))
        assertEquals(87, AaSteeringMediaKeys.normalize(16, false))
        assertEquals(87, AaSteeringMediaKeys.normalize(15, true))
        assertEquals(88, AaSteeringMediaKeys.normalize(16, true))
        assertEquals(85, AaSteeringMediaKeys.normalize(14, false))
        assertEquals(85, AaSteeringMediaKeys.normalize(79, false))
        assertEquals(87, AaSteeringMediaKeys.normalize(87, true))
        assertEquals(88, AaSteeringMediaKeys.normalize(88, true))
        assertEquals(84, AaSteeringMediaKeys.normalize(84, false))
        assertEquals(231, AaSteeringMediaKeys.normalize(231, false))
    }

    @Test fun aaVisibleOwnsMediaEvenBeforePlaybackMetadataArrives() {
        for (code in listOf(85, 87, 88, 126, 127)) {
            assertTrue(AaSteeringMediaKeys.routeToAa(code, aaVisible = true,
                aaConnected = false, ftPlaying = true))
        }
    }

    @Test fun aaPlaybackStillOwnsSteeringKeysWhenFtScreenIsVisible() {
        for (code in listOf(85, 87, 88, 126, 127)) {
            assertTrue(AaSteeringMediaKeys.routeToAa(code, aaVisible = false,
                aaConnected = true, ftPlaying = false))
        }
    }

    @Test fun ownFtPlayerTakesPriorityOverBackgroundAaPlayback() {
        assertFalse(AaSteeringMediaKeys.routeToAa(87, aaVisible = false,
            aaConnected = true, ftPlaying = true))
    }

    @Test fun missingPlaybackMetadataStillRoutesToAa() {
        // AA music status can be missing while Yandex Music is still playing.
        assertTrue(AaSteeringMediaKeys.routeToAa(87, aaVisible = false,
            aaConnected = true, ftPlaying = false))
        assertTrue(AaSteeringMediaKeys.routeToAa(88, aaVisible = false,
            aaConnected = true, ftPlaying = false))
        assertFalse(AaSteeringMediaKeys.routeToAa(87, aaVisible = false,
            aaConnected = false, ftPlaying = false))
    }

    @Test fun navigationAndVoiceKeysAreNeverCapturedAsMedia() {
        for (code in listOf(3, 4, 19, 20, 84, 219, 231)) {
            assertFalse(AaSteeringMediaKeys.isMedia(code))
            assertFalse(AaSteeringMediaKeys.routeToAa(code, aaVisible = true,
                aaConnected = true, ftPlaying = false))
        }
    }
}
