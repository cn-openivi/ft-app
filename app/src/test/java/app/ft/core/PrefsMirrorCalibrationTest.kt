package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PrefsMirrorCalibrationTest {
    private fun setup(): Pair<Prefs, SharedPreferences> {
        val context = mock(Context::class.java)
        val store = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(store)
        return Prefs(context) to store
    }

    @Test fun savedCalibrationEnabledByDefaultWithoutErasingOldProfiles() {
        val (prefs, store) = setup()
        `when`(store.getBoolean("mirrorUseCalibration", true)).thenReturn(true)
        assertTrue(prefs.mirrorUseCalibration)
        verify(store).getBoolean("mirrorUseCalibration", true)
    }

    @Test fun explicitUserOptOutRemainsHonored() {
        val (prefs, store) = setup()
        `when`(store.getBoolean("mirrorUseCalibration", true)).thenReturn(false)
        assertFalse(prefs.mirrorUseCalibration)
        verify(store).getBoolean("mirrorUseCalibration", true)
    }
}
