package app.ft.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvcNalScanTest {
    @Test fun annexBConfigAndIdr() {
        val bytes = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00,
            0, 0, 1, 0x68, 0x11,
            0, 0, 0, 1, 0x65, 0x55
        )
        assertEquals(setOf(7, 8, 5), AvcNalScan.types(bytes))
    }

    @Test fun detectsEmittedEncoderSpsLevelAndProfileAcrossNalFramings() {
        assertEquals(66 to 31, AvcNalScan.spsProfileLevel(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f)))
        assertEquals(100 to 40, AvcNalScan.spsProfileLevel(
            byteArrayOf(0, 0, 1, 0x67, 0x64, 0x00, 0x28)))
        assertEquals(66 to 40, AvcNalScan.spsProfileLevel(
            byteArrayOf(0, 0, 0, 4, 0x67, 0x42, 0, 0x28)))
        assertEquals(null, AvcNalScan.spsProfileLevel(byteArrayOf(0, 0, 0, 1, 0x68, 0x22)))
    }

    @Test fun lengthPrefixedUnits() {
        val bytes = byteArrayOf(0, 0, 0, 2, 0x67, 0x42, 0, 0, 0, 2, 0x68, 0x22)
        assertEquals(setOf(7, 8), AvcNalScan.types(bytes))
    }

    @Test fun standaloneAvcConfigurationNal() {
        assertEquals(setOf(7), AvcNalScan.types(byteArrayOf(0x67, 0x42)))
        assertEquals(setOf(5), AvcNalScan.types(byteArrayOf(0, 0, 1, 0x65, 0x33)))
    }

    @Test fun opaqueOrEmptyBytesDoNotClaimSpsPpsIdr() {
        assertTrue(AvcNalScan.types(byteArrayOf()).isEmpty())
        assertTrue(AvcNalScan.types(byteArrayOf(0, 0, 0, 0)).isEmpty())
        assertEquals(setOf(1), AvcNalScan.types(byteArrayOf(1, 2, 3)))
    }
}
