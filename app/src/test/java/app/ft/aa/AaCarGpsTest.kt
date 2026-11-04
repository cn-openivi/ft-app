package app.ft.aa

import app.ft.core.ProtoReader
import app.ft.core.VehicleLocation
import org.junit.Assert.*
import org.junit.Test

class AaCarGpsTest {
    private fun car(latitude: Double, longitude: Double, receivedAtMs: Long = 1_000L) =
        VehicleLocation(source = "ICCOA", receivedAtMs = receivedAtMs,
            latitude = latitude, longitude = longitude,
            coordinatesUnit = "ANDROID_LOCATION_WGS84")

    @Test fun iccoaToAndroidAutoUsesDegreesE7AndRequiredFields() {
        val packet = AaCarGps.event(car(55.755826, 37.617299), 1_500L)!!
        val gps = ProtoReader(packet).message(1)!!
        assertEquals(557_558_260, gps.int(2))
        assertEquals(376_172_990, gps.int(3))
        assertFalse(gps.has(1))
        assertEquals(setOf(2, 3), gps.fields.keys)
    }

    @Test fun positiveWgs84GpsUsesExactOfficialProtobufWireBytes() {
        // Independently serialized with the reference Google Protobuf encoder.
        // Independent AA LocationData proto2 latitude_e7=557558260, longitude_e7=376172990.
        val packet = AaCarGps.event(car(55.755826, 37.617299), 1_500L)!!
        assertArrayEquals(byteArrayOf(
            10, 12, 16, -12, -45, -18, -119, 2, 24, -66, -29, -81, -77, 1
        ), packet)
    }

    @Test fun zeroSatelliteOemLocationStillUsesVerifiedAaLocationWire() {
        // A fresh OEM WGS84 location with zero satellite counters has the
        // same exact AA payload as any other fresh coordinate. The counters
        // must not silently erase valid OEM positions or imply actual DR.
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.755800, 37.617200, 1_000L)
            .copy(satellitesUsed = 0L, satellitesVisible = 0L), 1_000L))
        assertNull(gate.accept(car(55.755810, 37.617210, 1_100L)
            .copy(satellitesUsed = 0L, satellitesVisible = 0L), 1_100L))
        val packet = gate.accept(car(55.755826, 37.617299, 1_200L)
            .copy(satellitesUsed = 0L, satellitesVisible = 0L), 1_200L)
        assertArrayEquals(byteArrayOf(
            10, 12, 16, -12, -45, -18, -119, 2, 24, -66, -29, -81, -77, 1
        ), packet)
    }

    @Test fun negativeWgs84LatitudeUsesCanonicalTenByteInt32Encoding() {
        // Reference Google Protobuf encoder, not an FT ProtoReader round-trip.
        val packet = AaCarGps.event(car(-33.8688, 151.2093), 1_500L)!!
        assertArrayEquals(byteArrayOf(
            10, 17, 16, -128, -112, -64, -34, -2, -1, -1, -1,
            -1, 1, 24, -56, -22, -126, -47, 5
        ), packet)
        val parsed = ProtoReader(packet).message(1)!!
        assertEquals(-338_688_000, parsed.int(2))
        assertEquals(1_512_093_000, parsed.int(3))
    }

    @Test fun westAndSouthWgs84UseE7WithSignedTenByteVarints() {
        // Reference proto2 LocationData required int32 fields 2/3 with negative
        // int32 sign-extended to 64-bit canonical varints.
        val bytes = AaCarGps.event(car(-33.8688, -70.6693), 1_500L)!!
        assertArrayEquals(byteArrayOf(
            10, 22, 16, -128, -112, -64, -34, -2, -1, -1, -1, -1, 1,
            24, -8, -16, -126, -81, -3, -1, -1, -1, -1, 1
        ), bytes)
        val gps = ProtoReader(bytes).message(1)!!
        assertEquals(-338_688_000, gps.int(2))
        assertEquals(-706_693_000, gps.int(3))
    }

    @Test fun rejectMissingStaleAndUnverifiedCoordinateReference() {
        assertNull(AaCarGps.event(null, 1_500L))
        assertNull(AaCarGps.event(car(55.75, 37.62), 4_100L))
        assertNull(AaCarGps.event(car(91.0, 20.0), 1_500L))
        assertNull(AaCarGps.event(car(0.0, 0.0), 1_500L))
        assertNull(AaCarGps.event(car(Double.NaN, 20.0), 1_500L))
        assertNull(AaCarGps.event(car(55.0, 180.1), 1_500L))
        assertNull(AaCarGps.event(car(55.0, 20.0).copy(coordinatesUnit = "UNKNOWN"), 1_500L))
        assertNull(AaCarGps.event(car(55.0, 20.0).copy(source = "BAIDU"), 1_500L))
    }

    @Test fun serviceDiscoveryAdvertisesCarLocationWithOtherSensors() {
        val response = AaMessages.serviceDiscoveryResponse(
            1280, 720, 30, 160, true, "RAV4", true, advertiseCarLocation = true)
        val config = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }.message(2)!!
        val types = config.messages(1).map { it.int(1) }.toSet()
        assertTrue(AaProtocol.SENSOR_LOCATION in types)
        assertTrue(AaProtocol.SENSOR_NIGHT_MODE in types)
        assertTrue(AaProtocol.SENSOR_DRIVING_STATUS in types)
    }

    @Test fun gateRequiresThreeDistinctConsistentFixes() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.7558, 37.6172, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        assertNull(gate.accept(car(55.75582, 37.61722, 1200), 1300))
        assertNotNull(gate.accept(car(55.75583, 37.61723, 1400), 1400))
    }

    @Test fun unchangedHighFrequencyPollingNeverResendsAndStillExpires() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        val sameFix = car(55.75582, 37.61722, 1200)
        // A receiver can poll at 10 Hz while OEM updates GNSS once a second.
        for (now in 1300L..4100L step 100L)
            assertNull("duplicate car GNSS packet at $now", gate.accept(sameFix, now))
        // Optimized fast path must never keep a stale position alive.
        assertNull(gate.accept(sameFix, 4300))
        assertNull(gate.accept(car(55.75583, 37.61723, 5000), 5000))
        assertNull(gate.accept(car(55.75584, 37.61724, 5100), 5100))
        assertNotNull(gate.accept(car(55.75585, 37.61725, 5200), 5200))
    }

    @Test fun gateRejectsTeleportAndReacquires() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        // A 20 km teleport in 100 ms is impossible for the car.
        assertNull(gate.accept(car(55.9300, 37.6173, 1300), 1300))
        assertNull(gate.accept(car(55.93001, 37.61731, 1400), 1400))
        // Three wrong fixes in the same distant location remain a teleport.
        assertNull(gate.accept(car(55.93002, 37.61732, 1500), 1500))
        // Losing the OEM fix cannot by itself make a distant fake trustworthy.
        assertNull(gate.accept(null, 5_000))
        assertNull(gate.accept(car(55.93003, 37.61733, 5_100), 5_100))
        assertNull(gate.accept(car(55.93004, 37.61734, 5_200), 5_200))
        assertNull(gate.accept(car(55.93005, 37.61735, 5_300), 5_300))
        // A plausible real fix near the trusted location can still recover.
        assertNull(gate.accept(car(55.75583, 37.61723, 5_400), 5_400))
        assertNull(gate.accept(car(55.75584, 37.61724, 5_500), 5_500))
        assertNotNull(gate.accept(car(55.75585, 37.61725, 5_600), 5_600))
    }

    @Test fun genuineSensorGapAllowsOnlyPhysicallyPlausibleReacquisition() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        // A 4.8-second tunnel exit plausibly moved ~90 metres, not 19 km.
        assertNull(gate.accept(car(55.75660, 37.6173, 6000), 6000))
        assertNull(gate.accept(car(55.75661, 37.61731, 6100), 6100))
        assertNotNull(gate.accept(car(55.75662, 37.61732, 6200), 6200))
    }

    @Test fun postBlackoutStableSpoofClusterNeverClearsLastTrustedLocation() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1_000), 1_000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1_100), 1_100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1_200), 1_200))
        // No car GPS for 60 seconds; this is not evidence that a sudden
        // ~19 km new position can be trusted (under any normal car speed).
        assertNull(gate.accept(null, 61_200))
        for (i in 0..12) {
            val time = 61_300L + i * 100L
            assertNull("AA accepted spoofed post-gap location at $time",
                gate.accept(car(55.930 + i * 0.000001, 37.6173, time), time))
        }
        // Previously delivered near-location is still the comparison anchor.
        assertNull(gate.accept(car(55.75583, 37.61723, 62_700), 62_700))
        assertNull(gate.accept(car(55.75584, 37.61724, 62_800), 62_800))
        assertNotNull(gate.accept(car(55.75585, 37.61725, 62_900), 62_900))
    }

    @Test fun explicitNewSensorSessionResetDoesNotReuseAnOldCarAnchor() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1_000), 1_000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1_100), 1_100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1_200), 1_200))
        gate.reset() // Explicit new session; not an ordinary GNSS blackout.
        assertNull(gate.accept(car(55.930, 37.6173, 2_000), 2_000))
        assertNull(gate.accept(car(55.93001, 37.61731, 2_100), 2_100))
        assertNotNull(gate.accept(car(55.93002, 37.61732, 2_200), 2_200))
    }

    @Test fun returnsToTrustedPositionAfterLongQuarantinedStream() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        // Sustained false cluster for >3 seconds: the trusted anchor must survive.
        for (t in 1300L..4800L step 100L)
            assertNull(gate.accept(car(55.9300, 37.6173, t), t))
        // Three fresh, mutually plausible fixes return near the trusted point.
        assertNull(gate.accept(car(55.75583, 37.61723, 4900), 4900))
        assertNull(gate.accept(car(55.75584, 37.61724, 5000), 5000))
        assertNotNull(gate.accept(car(55.75585, 37.61725, 5100), 5100))
    }

    @Test fun prolongedFalseCoordinateClusterNeverPassesWithoutActualLoss() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1000), 1000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1100), 1100))
        assertNotNull(gate.accept(car(55.75582, 37.61722, 1200), 1200))
        // 2 km false offset is stable for multiple seconds but remains implausible.
        for (t in 1300L..3300L step 100L) {
            val sample = car(55.77382, 37.61722, t)
            assertNull("unsafe coordinate released at $t", gate.accept(sample, t))
        }
    }

    @Test fun gearInfoPacketsCannotRepairStaleGpsOrDiscardTrustedAnchor() {
        val gate = AaGpsGate()
        assertNull(gate.accept(car(55.75580, 37.61720, 1_000), 1_000))
        assertNull(gate.accept(car(55.75581, 37.61721, 1_100), 1_100))
        val last = car(55.75582, 37.61722, 1_200)
        assertNotNull(gate.accept(last, 1_200))
        // GearInfo traffic keeps receivedAtMs fresh, but OEM GPS
        // positionAtMs remains stale during an underground blackout.
        for (now in 5_000L..12_000L step 1_000L) {
            val gearOnly = last.copy(
                receivedAtMs = now, positionAtMs = 1_200L,
                gearSpeedRaw = (now / 1_000L), gearAtMs = now)
            assertNull(gate.accept(gearOnly, now))
        }
        // Three internally consistent but impossible GPS fixes must not
        // acquire AA merely because GearInfo has been flowing all along.
        assertNull(gate.accept(car(55.93000, 37.61730, 12_100), 12_100))
        assertNull(gate.accept(car(55.93001, 37.61731, 12_200), 12_200))
        assertNull(gate.accept(car(55.93002, 37.61732, 12_300), 12_300))
        assertNull(gate.accept(car(55.75583, 37.61723, 12_400), 12_400))
        assertNull(gate.accept(car(55.75584, 37.61724, 12_500), 12_500))
        assertNotNull(gate.accept(car(55.75585, 37.61725, 12_600), 12_600))
    }

    @Test fun baiduModeMustNotAdvertiseUnusableGps() {
        val response = AaMessages.serviceDiscoveryResponse(1280, 720, 30, 160, true, "RAV4", true)
        val config = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }.message(2)!!
        val types = config.messages(1).map { it.int(1) }.toSet()
        assertFalse(AaProtocol.SENSOR_LOCATION in types)
        assertTrue(AaProtocol.SENSOR_DRIVING_STATUS in types)
    }

    @Test fun subscribedAaGpsDoesNotDeclareOemLossBeforeSevenSeconds() {
        val gate = AaGpsAvailabilityGate(startedElapsedMs = 10_000L)
        assertFalse(gate.shouldReportUnavailable(13_500L))
        assertFalse(gate.shouldReportUnavailable(16_999L))
        assertTrue(gate.shouldReportUnavailable(17_000L))
        assertFalse(gate.shouldReportUnavailable(18_000L))
        // Existing AA SensorError(LOCATION=1, UNAVAILABLE=2) exact proto body.
        assertArrayEquals(byteArrayOf(8, 1, 16, 2), AaCarGps.sensorError(2))
    }

    @Test fun lateGearInfoCannotResetAaLossTimerAndFreshOemFixRecoversOnce() {
        val gate = AaGpsAvailabilityGate(startedElapsedMs = 2_000L)
        assertFalse(gate.onDeliveredLocation(3_000L))
        // Normal GearInfo updates every 100ms do not call
        // onDeliveredLocation; AA only counts accepted GPS packets.
        for (now in 3_100L..9_900L step 100L)
            assertFalse(gate.shouldReportUnavailable(now))
        assertTrue(gate.shouldReportUnavailable(10_000L))
        assertFalse(gate.shouldReportUnavailable(10_500L))
        assertTrue(gate.onDeliveredLocation(11_500L))
        assertArrayEquals(byteArrayOf(8, 1, 16, 1), AaCarGps.sensorError(1))
        assertFalse(gate.onDeliveredLocation(12_000L))
        // The source remained live and delivered ANOTHER accepted OEM fix
        // at 12_000ms. Its age, not the first recovery event at 11_500ms,
        // sets the next loss threshold to 19_000ms.
        assertFalse(gate.shouldReportUnavailable(18_999L))
        assertTrue(gate.shouldReportUnavailable(19_000L))
    }

    @Test fun backwardWallClockChangeCannotFakeAaGpsLoss() {
        val gate = AaGpsAvailabilityGate(startedElapsedMs = 50_000L)
        assertFalse(gate.shouldReportUnavailable(49_000L))
        assertFalse(gate.shouldReportUnavailable(56_999L))
        assertTrue(gate.shouldReportUnavailable(57_000L))
    }

    @Test fun gpsSensorErrorMatchesAndroidAutoWireSchema() {
        val p = ProtoReader(AaCarGps.sensorError(2))
        assertEquals(1, p.int(1))
        assertEquals(2, p.int(2))
    }
}
