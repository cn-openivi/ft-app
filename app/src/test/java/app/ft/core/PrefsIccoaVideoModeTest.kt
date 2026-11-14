package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** Direct H264 is default unless the owner previously chose normal composition. */
class PrefsIccoaVideoModeTest {
    @Test fun directIsDefaultAndExplicitNormalModeIsRespected() {
        val context = mock(Context::class.java)
        val store = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(store)
        `when`(store.getInt("iccoaVideoMode", 1)).thenReturn(1, 0, -1, 99)
        val prefs = Prefs(context)
        assertEquals(1, prefs.iccoaVideoMode)
        assertEquals(0, prefs.iccoaVideoMode)
        assertEquals(0, prefs.iccoaVideoMode)
        assertEquals(1, prefs.iccoaVideoMode)
        verify(store, never()).edit()
    }

    @Test fun verifiedVehicleExplicitNormalOverridesGlobalDirectDefault() {
        val context = mock(Context::class.java)
        val store = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(store)
        `when`(store.getInt("iccoaVideoMode", 1)).thenReturn(1)
        val receiverId = "a".repeat(64)
        `when`(store.getInt("iccoaVideoMode.receiver.$receiverId", 1)).thenReturn(0)
        val prefs = Prefs(context)
        assertEquals(0, prefs.iccoaVideoModeFor(receiverId))
        verify(store, never()).edit()
    }

    @Test fun persistedModeIsLimitedToTwoSupportedValues() {
        val context = mock(Context::class.java)
        val store = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(context.getSharedPreferences("ft", Context.MODE_PRIVATE)).thenReturn(store)
        `when`(store.edit()).thenReturn(editor)
        `when`(editor.putInt(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt())).thenReturn(editor)
        val prefs = Prefs(context)
        prefs.iccoaVideoMode = -5
        prefs.iccoaVideoMode = 15
        verify(editor).putInt("iccoaVideoMode", 0)
        verify(editor).putInt("iccoaVideoMode", 1)
    }
}
