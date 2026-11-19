package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`

class PrefsAaStandardRasterTest {
    private fun prefs(w: Int, h: Int): Pair<Prefs, SharedPreferences> {
        val ctx = mock(Context::class.java)
        val sp = mock(SharedPreferences::class.java)
        `when`(ctx.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(sp)
        `when`(sp.getInt("aaWidth", 1280)).thenReturn(w)
        `when`(sp.getInt("aaHeight", 720)).thenReturn(h)
        return Prefs(ctx) to sp
    }

    @Test fun saved1920x1008CannotReachCodecSdpOrTouchConsumers() {
        val (p, sp) = prefs(1920, 1008)
        assertEquals(1920 to 1080, p.aaWidth to p.aaHeight)
        verify(sp, never()).edit() // no preference reset or trust deletion
    }

    @Test fun manual720RemainsExactly720() {
        val (p, _) = prefs(1280, 720)
        assertEquals(1280 to 720, p.aaWidth to p.aaHeight)
    }

    @Test fun anyLegacyCustomSizeIsReadAsAStandardVideoRaster() {
        val supported = setOf(800 to 480, 1280 to 720, 1920 to 1080)
        for ((w, h) in listOf(1920 to 1008, 1920 to 720,
            1138 to 640, 1600 to 900, 3840 to 2160, 320 to 240)) {
            val (p, _) = prefs(w, h)
            assertTrue((p.aaWidth to p.aaHeight) in supported)
        }
    }
}
