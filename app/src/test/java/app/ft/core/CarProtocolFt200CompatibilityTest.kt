package app.ft.core

import org.junit.Assert.assertEquals
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
}
