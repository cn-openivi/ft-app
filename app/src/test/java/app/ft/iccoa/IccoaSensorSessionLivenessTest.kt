package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaSensorSessionLivenessTest {
    @Test fun freshGearButStaleGpsCannotPretendCarLocationIsAvailable() {
        val evidence = IccoaSensorSessionLiveness()
        evidence.onGear(accepted = true)
        // OEM sends a protobuf, but its time gate rejects a cached fix.
        evidence.onGps(accepted = false)
        evidence.onGps(accepted = false)
        assertEquals(2L, evidence.gpsFrames)
        assertEquals(0L, evidence.acceptedGpsFrames)
        assertEquals(1L, evidence.acceptedGearFrames)
        assertTrue(evidence.gearWithoutAcceptedGps())
        // A truly fresh accepted frame ends the missing-location condition.
        evidence.onGps(accepted = true)
        assertEquals(1L, evidence.acceptedGpsFrames)
        assertFalse(evidence.gearWithoutAcceptedGps())
    }

    @Test fun reconnectRestartsSensorLivenessEvenAfterPriorSuccessfulSession() {
        val evidence = IccoaSensorSessionLiveness()
        evidence.onGear(accepted = true)
        evidence.onGps(accepted = true)
        assertFalse(evidence.gearWithoutAcceptedGps())
        // Old service-wide counters previously suppressed this warning
        // forever after one earlier car session saw a GPS packet.
        evidence.reset()
        assertEquals(0L, evidence.gpsFrames)
        assertEquals(0L, evidence.acceptedGpsFrames)
        assertEquals(0L, evidence.gearFrames)
        assertFalse(evidence.gearWithoutAcceptedGps())
        evidence.onGear(accepted = true)
        assertTrue(evidence.gearWithoutAcceptedGps())
        // Invalid GearInfo must not be labeled as valid, but it still
        // demonstrates that the ICCOA SENSOR transport lane is active.
        evidence.reset()
        evidence.onGear(accepted = false)
        assertEquals(0L, evidence.acceptedGearFrames)
        assertEquals(1L, evidence.gearFrames)
        assertTrue(evidence.gearWithoutAcceptedGps())
    }

    @Test fun noSensorTrafficIsNotFalseProofOfLiveSensorTransport() {
        val evidence = IccoaSensorSessionLiveness()
        assertFalse(evidence.gearWithoutAcceptedGps())
        evidence.onGps(accepted = false)
        assertFalse(evidence.gearWithoutAcceptedGps())
    }
}
