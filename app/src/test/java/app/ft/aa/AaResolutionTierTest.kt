package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaResolutionTierTest {
    @Test fun onlyImplementedAndroidAutoRasterTiersAreExposed() {
        val actual = AaResolutionTier.entries
        assertEquals(listOf(
            AaResolutionTier.AUTO, AaResolutionTier.P480,
            AaResolutionTier.P720, AaResolutionTier.P1080
        ), actual)
        assertEquals(800 to 480, AaResolutionTier.P480.width to AaResolutionTier.P480.height)
        assertEquals(1280 to 720, AaResolutionTier.P720.width to AaResolutionTier.P720.height)
        assertEquals(1920 to 1080, AaResolutionTier.P1080.width to AaResolutionTier.P1080.height)
        assertEquals(0 to 0, AaResolutionTier.AUTO.width to AaResolutionTier.AUTO.height)
    }

    @Test fun autoIsReceiverNegotiatedNotAStoredManualResolution() {
        assertEquals(AaResolutionTier.AUTO,
            AaResolutionTier.fromStored(true, 1920, 1008))
        assertEquals(AaResolutionTier.AUTO,
            AaResolutionTier.fromStored(true, 1280, 720))
    }

    @Test fun oldExactManualResolutionsMapToTheirQualityLabels() {
        for (preset in AaResolutionTier.entries.filter { it != AaResolutionTier.AUTO }) {
            assertEquals(preset, AaResolutionTier.fromStored(
                false, preset.width, preset.height))
        }
    }

    @Test fun legacyFreeFormAndInvented320p640pCannotMasqueradeAsAaModes() {
        assertNull(AaResolutionTier.fromStored(false, 1920, 1008))
        assertNull(AaResolutionTier.fromStored(false, 320, 240))
        assertNull(AaResolutionTier.fromStored(false, 1138, 640))
        assertNull(AaResolutionTier.fromStored(false, 2340, 1080))
    }
}
