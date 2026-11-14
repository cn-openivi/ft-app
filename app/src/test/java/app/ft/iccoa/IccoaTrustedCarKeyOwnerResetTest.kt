package app.ft.iccoa

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class IccoaTrustedCarKeyOwnerResetTest {
    @Test fun primaryAndTokenSlotsOnlyDoNotIncludeUnrelatedData() {
        val hash = "a".repeat(64)
        assertEquals(listOf("car_auth_pk", "car_auth_pk_$hash"),
            IccoaTrustedCarKey.ownerTrustSlots(setOf("car_auth_pk", "car_auth_pk_$hash",
                "client_identity", "car_auth_pk_other", "sealed")))
    }
    @Test fun forgetCommitsAllAliasesAsSingleTransaction() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val hash = "a".repeat(64)
        `when`(context.getSharedPreferences("iccoa-trusted-quick-v1", Context.MODE_PRIVATE))
            .thenReturn(prefs)
        `when`(prefs.all).thenReturn(hashMapOf("car_auth_pk" to "sealed",
            "car_auth_pk_$hash" to "sealed2", "unrelated" to "retain"))
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val result = IccoaTrustedCarKey(context).clearOwnerApprovedKeys()
        assertEquals(2, result.aliasesFound)
        assertTrue(result.committed)
        verify(editor).remove("car_auth_pk")
        verify(editor).remove("car_auth_pk_$hash")
        verify(editor, never()).remove("unrelated")
        verify(editor, times(1)).commit()
    }
    @Test fun failedCommitDoesNotClaimTrustedVehicleWasForgotten() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(context.getSharedPreferences("iccoa-trusted-quick-v1", Context.MODE_PRIVATE))
            .thenReturn(prefs)
        `when`(prefs.all).thenReturn(hashMapOf("car_auth_pk" to "value"))
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false)
        assertFalse(IccoaTrustedCarKey(context).clearOwnerApprovedKeys().committed)
    }
}
