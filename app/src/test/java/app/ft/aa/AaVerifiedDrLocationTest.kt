package app.ft.aa

import app.ft.core.CarProtocol
import app.ft.core.GpsSourceMode
import app.ft.core.ProtoReader
import app.ft.dr.DeadReckoningEkf
import app.ft.dr.DrNavigationFix
import app.ft.dr.DrNavigationTelemetry
import org.junit.Assert.*
import org.junit.Test

class AaVerifiedDrLocationTest {
    private fun drFix(
        now: Long = 10_000L,
        accurate: Double = 20.0,
        proven: Boolean = true,
        selected: GpsSourceMode = GpsSourceMode.CAR
    ) = DrNavigationFix(
        55.755826, 37.617299, accurate, 90.0, selected, now,
        estimated = true, verifiedVehicleMotion = proven
    )

    @Test fun sdpFusedBitsAreExactAospLocationCharacterizationAndNotRaw() {
        assertEquals(0x100, AaLocationCharacterization.forSession(false))
        val fused = AaLocationCharacterization.forSession(true)
        assertEquals(0x93, fused)
        assertTrue(AaLocationCharacterization.permitsEstimatedPosition(fused))
        assertFalse(AaLocationCharacterization.permitsEstimatedPosition(0x100))
        val response = AaMessages.serviceDiscoveryResponse(
            1920, 1008, 30, 240, false, "XA60",
            advertiseCarLocation = true,
            locationCharacterization = fused)
        val sensorConfig = ProtoReader(response).messages(1).first {
            it.int(1) == AaProtocol.CH_SENSOR
        }.message(2)!!
        assertTrue(sensorConfig.messages(1).any {
            it.int(1) == AaProtocol.SENSOR_LOCATION
        })
        assertEquals(0x93, sensorConfig.int(2))
        assertFalse(sensorConfig.int(2) and 0x100 != 0)
    }

    @Test fun verifiedEkfToWgs84AaSensorEventHasExactMicrodegreesAndAccuracy() {
        val now = 10_000L
        val source = drFix(now)
        val packet = AaCarGps.fusedEvent(source, now, 0x93)!!
        // SensorEventIndication.location[0] {lat=55755826, lon=37617299,
        // accuracy=20}, independently matched against GPSLocation proto2.
        assertArrayEquals(byteArrayOf(
            10, 12, 16, -78, -120, -53, 26, 24, -109, -3, -9, 17, 32, 20
        ), packet)
        val gps = ProtoReader(packet).message(1)!!
        assertEquals(55_755_826, gps.int(2))
        assertEquals(37_617_299, gps.int(3))
        assertEquals(20, gps.int(4))
        assertEquals(setOf(2, 3, 4), gps.fields.keys)
    }

    @Test fun neverInjectDrUnderRawGpsCharacterizationOrPhoneSource() {
        assertNull(AaCarGps.fusedEvent(drFix(), 10_000L, 0x100))
        assertNull(AaCarGps.fusedEvent(drFix(selected=GpsSourceMode.PHONE),
            10_000L, 0x93))
        assertNull(AaCarGps.fusedEvent(drFix(proven=false), 10_000L, 0x93))
        assertNull(AaCarGps.fusedEvent(drFix(now=10_000L),
            11_501L, 0x93))
        assertNull(AaCarGps.fusedEvent(drFix(accurate=Double.NaN),
            10_000L, 0x93))
    }

    @Test fun sourceOnlyVerifiedEkfPredictionMayBecomeAndroidAutoLocation() {
        DrNavigationTelemetry.clear()
        val model = DeadReckoningEkf()
        assertTrue(model.anchor(0.0, 0.0, 0.0, 2.0))
        val inTunnel = model.predict(1.0, 10.0, 0.0, true)!!
        assertFalse(DrNavigationTelemetry.publishFromVerifiedEkf(
            55.75, 37.61, inTunnel, GpsSourceMode.CAR,
            wheelSpeedAndYawVerified=false, producedAtMs=10_000L))
        assertNull(DrNavigationTelemetry.current.value)
        assertTrue(DrNavigationTelemetry.publishFromVerifiedEkf(
            55.75, 37.61, inTunnel, GpsSourceMode.CAR,
            wheelSpeedAndYawVerified=true, producedAtMs=10_000L))
        val position = DrNavigationTelemetry.current.value!!
        assertTrue(position.estimated)
        assertTrue(position.latitude > 55.75)
        assertEquals(37.61, position.longitude, 0.000001)
        val packet = AaCarGps.fusedEvent(position, 10_000L, 0x93)
        assertNotNull(packet)
        assertTrue(ProtoReader(packet!!).message(1)!!.has(4))
        DrNavigationTelemetry.clear()
    }

    @Test fun noPhoneLocationAdvertisedIfUserSelectedPhoneGps() {
        assertFalse(GpsSourceMode.PHONE.advertisesCarLocation(CarProtocol.ICCOA))
    }

    @Test fun rawLocationDescriptorRemainsRawUntilVerifiedDrActuallyExists() {
        DrNavigationTelemetry.clear()
        val eligible = DrNavigationTelemetry.current.value?.eligibleForAa(10_000L) == true
        assertFalse(eligible)
        val flags = AaLocationCharacterization.forSession(eligible)
        assertEquals(0x100, flags)
    }
}
