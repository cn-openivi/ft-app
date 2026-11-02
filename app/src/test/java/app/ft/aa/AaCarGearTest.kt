package app.ft.aa

import app.ft.core.ProtoReader
import app.ft.core.VehicleLocation
import org.junit.Assert.*
import org.junit.Test

class AaCarGearTest {
    private fun gear(raw: Long?, t: Long = 1_000L) =
        VehicleLocation(source = "ICCOA", receivedAtMs = t,
            positionAtMs = 0L, gearRaw = raw, gearAtMs = t)

    @Test fun exactOemEnumsMapToAaWithoutFakingParkFromZeroSpeed() {
        val cases = listOf(1L to 101, 2L to 100, 3L to 102, 4L to 0)
        cases.forEach { (raw, translated) ->
            assertEquals(translated, AaCarGear.aaGear(gear(raw), 1_010L))
            assertEquals(translated,
                ProtoReader(AaCarGear.event(gear(raw), 1_010L)!!).message(8)!!.int(1))
        }
        assertNull(AaCarGear.event(gear(0L), 1_010L))
        assertNull(AaCarGear.event(gear(null).copy(gearSpeedRaw = 0L), 1_010L))
    }

    @Test fun expiredOrUnknownCarGearNeverSignalsPark() {
        assertNull(AaCarGear.event(gear(1L), 4_001L))
        assertNull(AaCarGear.event(gear(1L), 999L))
        assertNull(AaCarGear.event(null, 1_000L))
        assertNull(AaCarGear.event(gear(1L).copy(source = "BAIDU"), 1_010L))
        assertNull(AaCarGear.event(gear(101L), 1_010L))
    }

    @Test fun errorReferencesGearSensorNotGps() {
        val p = ProtoReader(AaCarGear.sensorError(2))
        assertEquals(8, p.int(1))
        assertEquals(2, p.int(2))
    }
}
