package app.ft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FT200 compatibility contract: existing Baidu/ICCOA selections and persisted IDs stay stable.
 * A new CarPlay backend must not silently renumber or replace either one.
 */
class CarProtocolFt200CompatibilityTest {
    @Test fun legacyTransportIdsRemainStable() {
        assertEquals("carlife", CarProtocol.CARLIFE.id)
        assertEquals("iccoa", CarProtocol.ICCOA.id)
    }

    @Test fun persistedLegacyValuesStillOpenTheSameBackend() {
        assertEquals(CarProtocol.CARLIFE, CarProtocol.fromId("carlife"))
        assertEquals(CarProtocol.ICCOA, CarProtocol.fromId("iccoa"))
    }

    @Test fun corruptOrUnknownStoredPreferenceStillDefaultsToBaidu() {
        assertEquals(CarProtocol.CARLIFE, CarProtocol.fromId(null))
        assertEquals(CarProtocol.CARLIFE, CarProtocol.fromId(""))
        assertEquals(CarProtocol.CARLIFE, CarProtocol.fromId("invalid-protocol"))
    }
    @Test fun thirdProtocolHasStableIdButCannotStartBeforeRealHandshake() {
        assertEquals("carplay", CarProtocol.CARPLAY.id)
        assertEquals(CarProtocol.CARPLAY, CarProtocol.fromExplicitId("carplay"))
        assertEquals(CarProtocol.CARPLAY, CarProtocol.fromId("carplay"))
        assertFalse(CarProtocol.CARPLAY.connectionReady)
        assertEquals(CarPlayExperimentalSelectionPolicy.Action.PRESERVE_LIVE_BACKEND_EXPERIMENT_ONLY,
            CarPlayExperimentalSelectionPolicy.choose(CarProtocol.CARLIFE, CarProtocol.CARPLAY))
        assertTrue(CarProtocol.CARLIFE.connectionReady)
        assertTrue(CarProtocol.ICCOA.connectionReady)
    }

    @Test fun invalidExplicitSelectionDoesNotSilentlyUseBaidu() {
        assertNull(CarProtocol.fromExplicitId(null))
        assertNull(CarProtocol.fromExplicitId(""))
        assertNull(CarProtocol.fromExplicitId("CARPLAY"))
        assertNull(CarProtocol.fromExplicitId("future"))
    }
}

