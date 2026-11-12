package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** Picture stream UI DPI is independent from Android Auto DPI. */
class PrefsCarDensityTest {
    private fun preferences(): Pair<Prefs, SharedPreferences> {
        val context = mock(Context::class.java)
        val store = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(store)
        return Prefs(context) to store
    }

    @Test fun freshFt181InstallUses240DpiForPictureApps() {
        val (prefs, store) = preferences()
        `when`(store.getInt("carDensity", 240)).thenReturn(240)

        assertEquals(240, Prefs.DEFAULT_CAR_DENSITY_DPI)
        assertEquals(240, prefs.carDensity)
        verify(store).getInt("carDensity", 240)
        verify(store, never()).edit()
    }

    @Test fun updatePreservesStoredDpiIncludingAnIntentional160() {
        val (prefs, store) = preferences()
        `when`(store.getInt("carDensity", 240)).thenReturn(160, 320)

        assertEquals(160, prefs.carDensity)
        assertEquals(320, prefs.carDensity)
        verify(store, never()).edit()
    }
}
