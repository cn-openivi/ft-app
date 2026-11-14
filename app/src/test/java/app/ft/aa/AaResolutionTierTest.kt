package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaResolutionTierTest {
    @Test fun requestedModesCoverAllUniversalQualityLabelsWithoutAliasCollision() {
        assertEquals(listOf(
            AaResolutionTier.AUTO,
            AaResolutionTier.P480,
            AaResolutionTier.P720,
            AaResolutionTier.P1080,
            AaResolutionTier.P2K_DCI,
            AaResolutionTier.P1440,
            AaResolutionTier.P4K_UHD
        ), AaResolutionTier.entries)

        assertEquals(800 to 480, AaResolutionTier.P480.width to AaResolutionTier.P480.height)
        assertEquals(1280 to 720, AaResolutionTier.P720.width to AaResolutionTier.P720.height)
        assertEquals(1920 to 1080, AaResolutionTier.P1080.width to AaResolutionTier.P1080.height)
        assertEquals(2048 to 1080, AaResolutionTier.P2K_DCI.width to AaResolutionTier.P2K_DCI.height)
        assertEquals(2560 to 1440, AaResolutionTier.P1440.width to AaResolutionTier.P1440.height)
        assertEquals(3840 to 2160, AaResolutionTier.P4K_UHD.width to AaResolutionTier.P4K_UHD.height)
    }

    @Test fun higherUserQualityTargetsNeverAdvertiseUnverifiedAaWireModes() {
        // AA 1440/4K enum entries exist in some APK snapshots, but their
        // runtime resolution mapping is not universally supported.
        for (tier in listOf(
            AaResolutionTier.P2K_DCI,
            AaResolutionTier.P1440,
            AaResolutionTier.P4K_UHD
        )) {
            assertTrue(tier.isExperimental)
            assertFalse(tier.verifiedWire)
            assertEquals(1920 to 1080, tier.effectiveWireSize)
        }
        assertEquals(800 to 480, AaResolutionTier.P480.effectiveWireSize)
        assertEquals(1280 to 720, AaResolutionTier.P720.effectiveWireSize)
        assertEquals(1920 to 1080, AaResolutionTier.P1080.effectiveWireSize)
    }

    @Test fun autoNegotiatesWithAnyReceiverNotManufacturerSpecificPreset() {
        assertEquals(AaResolutionTier.AUTO, AaResolutionTier.fromStored(true, 1920, 1008))
        assertEquals(AaResolutionTier.AUTO, AaResolutionTier.fromStored(true, 1280, 720))
        assertEquals(1280 to 720, AaResolutionTier.AUTO.effectiveWireSize)
    }

    @Test fun legacyExactModesAreRecognizedButCustomGeometryIsNeverMisreported() {
        for (tier in AaResolutionTier.entries.filter { it.verifiedWire && it != AaResolutionTier.AUTO }) {
            assertEquals(tier, AaResolutionTier.fromStored(false, tier.width, tier.height))
        }
        for ((w, h) in listOf(
            1920 to 1008, 320 to 240, 1138 to 640,
            2340 to 1080, 2048 to 1080, 2560 to 1440, 3840 to 2160
        )) assertNull(AaResolutionTier.fromStored(false, w, h))
    }

    @Test fun savedDesiredTierPersistsSeparatelyFromCappedEffectiveRaster() {
        for (tier in AaResolutionTier.entries) {
            assertEquals(tier, AaResolutionTier.fromSavedKey(tier.name))
        }
        assertNull(AaResolutionTier.fromSavedKey(""))
        assertNull(AaResolutionTier.fromSavedKey("RAV4_4K"))
        assertNotEquals(AaResolutionTier.P2K_DCI, AaResolutionTier.P1440)
    }
}
