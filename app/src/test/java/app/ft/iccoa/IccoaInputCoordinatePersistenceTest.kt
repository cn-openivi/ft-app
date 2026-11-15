package app.ft.iccoa

import android.content.Context
import android.content.SharedPreferences
import app.ft.projection.IccoaCalibrationScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Verify the actual production SharedPreferences-backed reconnect path. */
class IccoaInputCoordinatePersistenceTest {
    private val storage = linkedMapOf<String, String>()
    private val context = Mockito.mock(Context::class.java)
    private val pref = Mockito.mock(SharedPreferences::class.java)
    private val editor = Mockito.mock(SharedPreferences.Editor::class.java)

    init {
        Mockito.`when`(context.getSharedPreferences("ft-iccoa-uibc-domain", Context.MODE_PRIVATE))
            .thenReturn(pref)
        Mockito.`when`(pref.getString(Mockito.anyString(), Mockito.isNull()))
            .thenAnswer { storage[it.getArgument<String>(0)] }
        Mockito.`when`(pref.edit()).thenReturn(editor)
        Mockito.`when`(editor.putString(Mockito.anyString(), Mockito.anyString()))
            .thenAnswer {
                storage[it.getArgument<String>(0)] = it.getArgument<String>(1)
                editor
            }
        Mockito.`when`(editor.remove(Mockito.anyString()))
            .thenAnswer {
                storage.remove(it.getArgument<String>(0))
                editor
            }
        Mockito.`when`(editor.commit()).thenReturn(true)
    }

    @After fun clearReceiverIdentity() = IccoaCalibrationScope.clear()

    @Test fun cannotStoreOrLoadFromUnverifiedReceiverIdentity() {
        IccoaCalibrationScope.clear()
        assertEquals(IccoaInputCoordinateSpace.Mode.UNKNOWN,
            IccoaInputCoordinateSpace.remembered(context))
        assertFalse(IccoaInputCoordinateSpace.rememberVerified(
            context, IccoaInputCoordinateSpace.Mode.VIDEO_STREAM))
        assertTrue(storage.isEmpty())
    }

    @Test fun reconnectRecoversExactVerifiedDomainButDifferentReceiverCannotReadIt() {
        val receiverA = "a".repeat(64)
        val receiverB = "b".repeat(64)
        IccoaCalibrationScope.activate(receiverA, false)
        assertTrue(IccoaInputCoordinateSpace.rememberVerified(
            context, IccoaInputCoordinateSpace.Mode.VIDEO_STREAM))
        assertEquals(IccoaInputCoordinateSpace.Mode.VIDEO_STREAM,
            IccoaInputCoordinateSpace.remembered(context))

        IccoaCalibrationScope.clear()
        IccoaCalibrationScope.activate(receiverB, false)
        assertEquals(IccoaInputCoordinateSpace.Mode.UNKNOWN,
            IccoaInputCoordinateSpace.remembered(context))
        IccoaCalibrationScope.clear()
        IccoaCalibrationScope.activate(receiverA, false)
        assertEquals(IccoaInputCoordinateSpace.Mode.VIDEO_STREAM,
            IccoaInputCoordinateSpace.remembered(context))
    }

    @Test fun ownerForgetClearsCoordinateModeAlongWithWelcome() {
        val fingerprint = "a".repeat(64)
        IccoaCalibrationScope.activate(fingerprint, false)
        assertTrue(IccoaInputCoordinateSpace.rememberVerified(
            context, IccoaInputCoordinateSpace.Mode.NATIVE_DISPLAY))
        assertTrue(IccoaInputCoordinateSpace.forgetReceiver(
            context, "verified_${fingerprint}:welcome"))
        assertEquals(IccoaInputCoordinateSpace.Mode.UNKNOWN,
            IccoaInputCoordinateSpace.remembered(context))
    }
}
