package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaSensorSubscriptionsTest {
    @Test fun recordsOnlyRequestedSensorTypesNoLocationData() {
        val audit = AaSensorSubscriptions()
        audit.note(13)
        audit.note(10)
        audit.note(13)
        assertFalse(audit.has(1))
        assertEquals("NIGHT_MODE=1,DRIVING_STATUS=2", audit.summary())
        audit.note(1)
        assertTrue(audit.has(1))
        assertEquals("LOCATION=1,NIGHT_MODE=1,DRIVING_STATUS=2", audit.summary())
    }

    @Test fun recordsPotentialGearSpeedAndParkingRequestsWithoutFakingValues() {
        val audit = AaSensorSubscriptions()
        audit.note(8)
        audit.note(7)
        audit.note(3)
        audit.note(-1)
        audit.note(100)
        assertEquals("CAR_SPEED=1,PARKING_BRAKE=1,GEAR=1", audit.summary())
        audit.reset()
        assertEquals("NONE", audit.summary())
    }
}
