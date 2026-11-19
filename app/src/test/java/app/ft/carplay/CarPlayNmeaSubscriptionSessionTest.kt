package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayNmeaSubscriptionSessionTest {
    private val gate = CarPlayNmeaSubscriptionSession()
    private fun fix(t: Long, src: CarPlayMediaPreflight.PositionSource =
        CarPlayMediaPreflight.PositionSource.REAL_OEM_WGS84,
        fusion: Boolean = false) = CarPlayNmeaSentences.Fix(
        latitude = 51.5074, longitude = -0.1278, utcEpochMillis = t,
        source = src, verifiedFusion = fusion, speedMps = 10.0
    )

    @Test fun neitherAuthNorHuRequestCanBeInferredFromSource() {
        assertEquals(CarPlayNmeaSubscriptionSession.Activation.AUTHENTICATION_REQUIRED, gate.begin(1, false, true, true))
        assertEquals(CarPlayNmeaSubscriptionSession.Activation.HU_REQUEST_MISSING, gate.begin(2, true, false, true))
        assertEquals(CarPlayNmeaSubscriptionSession.Activation.PROFILE_NOT_CONFIRMED, gate.begin(3, true, true, false))
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.SESSION_MISSING_OR_CHANGED, gate.encode(3, fix(1000L), true).outcome)
    }

    @Test fun verifiedSessionEncodesButNeverSendsAndDropsDuplicateFix() {
        assertEquals(CarPlayNmeaSubscriptionSession.Activation.ACTIVATED_FOR_ENCODING_ONLY, gate.begin(4, true, true, true))
        val one = gate.encode(4, fix(1730000000000L), true)
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.ENCODED_NOT_SENT, one.outcome)
        assertNotNull(one.sentences)
        assertEquals(1730000000000L, one.fixUtcEpochMillis)
        assertTrue(one.sentences!!.gga.startsWith("$"+"GPGGA"))
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.FIX_NOT_NEWER, gate.encode(4, fix(1730000000000L), true).outcome)
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.FIX_NOT_NEWER, gate.encode(4, fix(1729999999999L), true).outcome)
        assertFalse(one.toString().contains("51.5074"))
    }

    @Test fun phoneSourceAndUnverifiedDrNeverQualify() {
        gate.begin(5, true, true, true)
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.SOURCE_REJECTED,
            gate.encode(5, fix(1730000000000L, CarPlayMediaPreflight.PositionSource.PHONE), true).outcome)
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.SOURCE_REJECTED,
            gate.encode(5, fix(1730000000000L, CarPlayMediaPreflight.PositionSource.VERIFIED_EKF_DR), true).outcome)
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.SOURCE_REJECTED,
            gate.encode(5, fix(1730000000000L), false).outcome)
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.ENCODED_NOT_SENT,
            gate.encode(5, fix(1730000000000L, CarPlayMediaPreflight.PositionSource.VERIFIED_EKF_DR, true), true).outcome)
    }

    @Test fun sessionChangeAndDelayedLossCallbacksAreFenced() {
        gate.begin(10, true, true, true)
        assertEquals(CarPlayNmeaSubscriptionSession.Activation.ACTIVATED_FOR_ENCODING_ONLY, gate.begin(11, true, true, true))
        gate.onSessionLost(10)
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.SESSION_MISSING_OR_CHANGED, gate.encode(10, fix(1000), true).outcome)
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.ENCODED_NOT_SENT, gate.encode(11, fix(1000), true).outcome)
        gate.onSessionLost(11)
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.SESSION_MISSING_OR_CHANGED, gate.encode(11, fix(2000), true).outcome)
        assertEquals(CarPlayNmeaSubscriptionSession.Activation.STALE_GENERATION, gate.begin(11, true, true, true))
    }

    @Test fun failedNewSessionRevokesPreviousAuthorization() {
        gate.begin(15, true, true, true)
        assertEquals(CarPlayNmeaSubscriptionSession.Activation.HU_REQUEST_MISSING, gate.begin(16, true, false, true))
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.SESSION_MISSING_OR_CHANGED, gate.encode(15, fix(1000), true).outcome)
        assertEquals(CarPlayNmeaSubscriptionSession.Outcome.SESSION_MISSING_OR_CHANGED, gate.encode(16, fix(1000), true).outcome)
    }
}
