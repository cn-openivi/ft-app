package app.ft.projection

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/** Physical FT187 regression: one center tap must not auto-verify whole HU. */
class IccoaTouchWelcomeFlowTest {
    private val receiver = "a".repeat(64)
    private val verified = "verified_" + receiver + ":welcome"
    private val stored = linkedMapOf<String, Boolean>()
    private val context = Mockito.mock(Context::class.java)
    private val welcomePrefs = Mockito.mock(SharedPreferences::class.java)
    private val modePrefs = Mockito.mock(SharedPreferences::class.java)
    private val editor = Mockito.mock(SharedPreferences.Editor::class.java)

    @Before fun prepare() {
        IccoaCalibrationScope.activate(receiver, false)
        IccoaTouchWelcome.cancel()
        CarTouchCalibration.cancel()
        Mockito.`when`(context.getSharedPreferences("ft-iccoa-touch-welcome", Context.MODE_PRIVATE))
            .thenReturn(welcomePrefs)
        Mockito.`when`(context.getSharedPreferences("ft-iccoa-uibc-domain", Context.MODE_PRIVATE))
            .thenReturn(modePrefs)
        Mockito.`when`(modePrefs.getString(Mockito.anyString(), Mockito.isNull()))
            .thenReturn("VIDEO_STREAM")
        Mockito.`when`(welcomePrefs.getBoolean(Mockito.anyString(), Mockito.anyBoolean()))
            .thenAnswer { stored[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
        Mockito.`when`(welcomePrefs.edit()).thenReturn(editor)
        Mockito.`when`(editor.putString(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(editor)
        Mockito.`when`(editor.putBoolean(Mockito.anyString(), Mockito.anyBoolean()))
            .thenAnswer {
                stored[it.getArgument<String>(0)] = it.getArgument<Boolean>(1)
                editor
            }
        Mockito.`when`(editor.commit()).thenReturn(true)
    }

    @After fun cleanup() {
        IccoaTouchWelcome.cancel()
        CarTouchCalibration.cancel()
        IccoaCalibrationScope.clear()
    }

    @Test fun oldCenterOnlyAcceptedFlagCannotSkipNewEdgeVerification() {
        stored[verified] = true // historical v187 "center is enough" persisted state
        IccoaTouchWelcome.begin(context, 2422, 1272)
        assertTrue(IccoaTouchWelcome.awaiting)

        val first = IccoaTouchWelcome.consume(context, CarTouch.DOWN, 1211, 636, 2422, 1272)
        assertTrue(first.consumed)
        assertFalse(first.completed)
        assertTrue(IccoaTouchWelcome.state.value.centerChecked)
        assertTrue(IccoaTouchWelcome.awaiting)
        assertFalse(stored[verified + ":spatial-check-v1"] ?: false)

        IccoaTouchWelcome.consume(context, CarTouch.UP, 1211, 636, 2422, 1272)
        val second = IccoaTouchWelcome.consume(context, CarTouch.DOWN,
            IccoaWelcomeTarget.rightX(2422), IccoaWelcomeTarget.rightY(1272),
            2422, 1272)
        assertTrue(second.consumed)
        assertTrue(second.completed)
        assertFalse(IccoaTouchWelcome.awaiting)
        assertTrue(stored[verified + ":spatial-check-v1"] == true)
        IccoaTouchWelcome.begin(context, 2422, 1272)
        assertFalse(IccoaTouchWelcome.awaiting)
    }

    @Test fun firstCenterStagesDomainButOnlyOffCenterProbeCommitsToPrefs() {
        Mockito.`when`(modePrefs.edit()).thenReturn(editor)
        IccoaTouchWelcome.begin(context, 2422, 1272)
        assertTrue(app.ft.iccoa.IccoaInputCoordinateSpace.stageCenterCandidate(
            context, app.ft.iccoa.IccoaInputCoordinateSpace.Mode.VIDEO_STREAM))
        val first = IccoaTouchWelcome.consume(context, CarTouch.DOWN,
            1211, 636, 2422, 1272)
        assertTrue(first.consumed)
        assertFalse(first.completed)
        // The former bug persisted VIDEO_STREAM *before* this point.
        Mockito.verify(modePrefs, Mockito.never()).edit()
        assertFalse(stored[verified + ":spatial-check-v1"] ?: false)

        IccoaTouchWelcome.consume(context, CarTouch.UP, 1211, 636, 2422, 1272)
        val second = IccoaTouchWelcome.consume(context, CarTouch.DOWN,
            IccoaWelcomeTarget.rightX(2422), IccoaWelcomeTarget.rightY(1272),
            2422, 1272)
        assertTrue(second.completed)
        Mockito.verify(modePrefs, Mockito.times(1)).edit()
        assertTrue(stored[verified + ":spatial-check-v1"] == true)
    }

    @Test fun formerFt187CentreOnlyCacheCannotBypassNewSpatialVerification() {
        Mockito.`when`(modePrefs.getString(Mockito.anyString(), Mockito.isNull()))
            .thenAnswer {
                val key = it.getArgument<String>(0)
                if (key.endsWith(":uibc-domain-v187")) "VIDEO_STREAM" else null
            }
        stored[verified] = true
        stored[verified + ":spatial-check-v1"] = true
        IccoaTouchWelcome.begin(context, 2422, 1272)
        assertTrue("legacy saved domain must not skip the two-probe migration",
            IccoaTouchWelcome.awaiting)
        Mockito.verify(modePrefs).getString(
            Mockito.contains(":uibc-domain-v190-spatial"), Mockito.isNull())
    }

    @Test fun geometryChangeBetweenSpatialProbesRestartsAtCentreAndDiscardsCandidate() {
        Mockito.`when`(modePrefs.getString(Mockito.anyString(), Mockito.isNull()))
            .thenReturn(null)
        IccoaTouchWelcome.begin(context, 2422, 1272)
        assertTrue(app.ft.iccoa.IccoaInputCoordinateSpace.stageCenterCandidate(
            context, app.ft.iccoa.IccoaInputCoordinateSpace.Mode.VIDEO_STREAM))
        IccoaTouchWelcome.consume(context, CarTouch.DOWN, 1211, 636, 2422, 1272)
        assertTrue(IccoaTouchWelcome.state.value.centerChecked)
        IccoaTouchWelcome.resetActiveProbesOnGeometryChange()
        assertFalse(IccoaTouchWelcome.state.value.centerChecked)
        assertEquals(IccoaTouchWelcome.Phase.CHECK, IccoaTouchWelcome.state.value.phase)
        Mockito.verify(modePrefs, Mockito.never()).edit()
        assertFalse(stored[verified + ":spatial-check-v1"] ?: false)
    }

    @Test fun twoFailedCenterHitsStartExistingFourPointOnHeadUnit() {
        IccoaTouchWelcome.begin(context, 2422, 1272)
        IccoaTouchWelcome.consume(context, CarTouch.DOWN, 200, 200, 2422, 1272)
        assertTrue(IccoaTouchWelcome.state.value.centerMismatch)
        assertEquals(IccoaTouchWelcome.Phase.CHECK, IccoaTouchWelcome.state.value.phase)
        assertEquals(0, CarTouchCalibration.state.value.step)
        IccoaTouchWelcome.consume(context, CarTouch.UP, 200, 200, 2422, 1272)
        IccoaTouchWelcome.consume(context, CarTouch.DOWN, 250, 225, 2422, 1272)
        assertEquals(IccoaTouchWelcome.Phase.FOUR_POINTS, IccoaTouchWelcome.state.value.phase)
        assertEquals(1, CarTouchCalibration.state.value.step)
        assertFalse(stored[verified + ":spatial-check-v1"] ?: false)
    }
}
