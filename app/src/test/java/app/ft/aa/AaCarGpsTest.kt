package app.ft.aa

import app.ft.core.ProtoReader
import app.ft.core.VehicleLocation
import app.ft.core.GpsSourceMode
import app.ft.dr.DrNavigationFix
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

    @Test fun authenticatedOemLocationIncludesExactSpeedE3AndBearingE6() {
        // FAW OEM: floor(Location.getSpeed() m/s) = 5 and
        // floor(Location.getBearing() degrees) = 90.
        // AA independent proto: GPS LocationData.speed_e3=6,
        // bearing_e6=7; both are optional signed int32 varints.
        val oem = car(1.0, 2.0).copy(
            speedRaw = 5L, headingRaw = 90L,
            speedKmh = 18f, headingDegrees = 90f,
            speedUnit = "ANDROID_LOCATION_MPS_FLOORED",
            headingUnit = "ANDROID_LOCATION_DEGREES_FLOORED"
        )
        val wire = AaCarGps.event(oem, 1_500L)!!
        assertArrayEquals(byteArrayOf(
            0x0a, 0x12, 0x10,
            0x80.toByte(), 0xad.toByte(), 0xe2.toByte(), 0x04,
            0x18, 0x80.toByte(), 0xda.toByte(), 0xc4.toByte(), 0x09,
            0x30, 0x88.toByte(), 0x27,
            0x38, 0x80.toByte(), 0x95.toByte(), 0xf5.toByte(), 0x2a
        ), wire)
        val gps = ProtoReader(wire).message(1)!!
        assertEquals(5_000, gps.int(6))
        assertEquals(90_000_000, gps.int(7))
        assertEquals(setOf(2, 3, 6, 7), gps.fields.keys)
    }

    @Test fun stationaryCarLocationKeepsZeroSpeedWithoutInventingNorthHeading() {
        // The FAW CarLink OEM floors Android Location speed and course. A
        // stationary 0-degree COG is not physical compass north.
        val stopped = car(1.0, 2.0).copy(
            speedRaw = 0L, headingRaw = 0L,
            speedUnit = "ANDROID_LOCATION_MPS_FLOORED",
            headingUnit = "ANDROID_LOCATION_DEGREES_FLOORED"
        )
        val body = ProtoReader(AaCarGps.event(stopped, 1_500L)!!).message(1)!!
        assertTrue(body.has(6))
        assertEquals(0, body.int(6))
        assertFalse(body.has(7)) // Optional COG unavailable when stopped.
        assertFalse(body.has(4)) // Unknown accuracy was not fabricated.
        assertFalse(body.has(5)) // Unknown altitude was not fabricated.
    }

    @Test fun genuineMovingNorthIsNotConfusedWithStoppedZeroBearing() {
        val moving = car(1.0, 2.0).copy(
            speedRaw = 5L, headingRaw = 0L,
            speedUnit = "ANDROID_LOCATION_MPS_FLOORED",
            headingUnit = "ANDROID_LOCATION_DEGREES_FLOORED"
        )
        val message = ProtoReader(AaCarGps.event(moving, 1_500L)!!).message(1)!!
        assertEquals(5_000, message.int(6))
        assertTrue(message.has(7))
        assertEquals(0, message.int(7))

        // Even a nonzero OEM bearing at zero speed cannot reliably describe
        // the moving direction. Raw OEM fields remain available in logs.
        val stopped = moving.copy(speedRaw = 0L, headingRaw = 173L)
        assertFalse(ProtoReader(AaCarGps.event(stopped, 1_500L)!!)
            .message(1)!!.has(7))
        val unverifiedSpeed = moving.copy(speedUnit = "UNKNOWN")
        assertFalse(ProtoReader(AaCarGps.event(unverifiedSpeed, 1_500L)!!)
            .message(1)!!.has(7))
    }

    @Test fun unknownOrUnrelatedSpeedAndBearingMustNotEnterAaLocation() {
        val baseline = AaCarGps.event(car(55.755826, 37.617299), 1_500L)!!
        val unrelatedGearInfo = car(55.755826, 37.617299).copy(
            gearSpeedRaw = 76L, speedRaw = 76L, headingRaw = 270L,
            speedKmh = 273.6f, headingDegrees = 270f
        )
        assertArrayEquals(baseline, AaCarGps.event(unrelatedGearInfo, 1_500L))
        val invalidVerified = unrelatedGearInfo.copy(
            speedUnit = "ANDROID_LOCATION_MPS_FLOORED",
            headingUnit = "ANDROID_LOCATION_DEGREES_FLOORED",
            speedRaw = 126L, headingRaw = 360L
        )
        assertArrayEquals(baseline, AaCarGps.event(invalidVerified, 1_500L))
        val gpsOnlySpeed = invalidVerified.copy(speedRaw = 0L)
        val packet = ProtoReader(AaCarGps.event(gpsOnlySpeed, 1_500L)!!).message(1)!!
        assertEquals(0, packet.int(6))
        assertFalse(packet.has(7))
    }

    @Test fun zeroSatelliteOemLocationStillUsesVerifiedAaLocationWire() {
        // A fresh OEM WGS84 location with zero satellite counters has the
        // same exact AA payload as any other fresh coordinate. The counters
        // must not silently erase valid OEM positions or imply actual DR.
        val gate = AaGpsGate()
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
        // Wall-clock age is not a reason to reject an authentic new ICCOA event.
        assertNotNull(AaCarGps.event(car(55.75, 37.62), 4_100L))
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

    @Test fun oemAltitudeAndSatelliteCountsUseCorrectAaFields() {
        val sample = car(55.755826, 37.617299).copy(
            altitudeMeters = 112.5,
            altitudeUnit = "ANDROID_LOCATION_METRES",
            satellitesVisible = 16L,
            satellitesUsed = 12L,
            antennaStateRaw = 3L,
            pdopRaw = 7L)
        val location = ProtoReader(AaCarGps.event(sample, 1_500L)!!).message(1)!!
        assertEquals(11_250, location.int(5))
        assertFalse(location.has(4)) // PDOP is not an accuracy measurement in metres.
        val gpsSatellites = ProtoReader(AaCarGps.satellitesEvent(sample, 1_500L)!!).message(21)!!
        assertEquals(16, gpsSatellites.int(1))
        assertEquals(12, gpsSatellites.int(2))
        assertEquals(setOf(1, 2), gpsSatellites.fields.keys)
        // Antenna state/PDOP have no equivalent fields in AA LOCATION or GPS_SATELLITES.
    }

    @Test fun negativeOemAltitudeUsesCanonicalSignedInt32() {
        val sample = car(55.7, 37.6).copy(
            altitudeMeters = -50.25, altitudeUnit = "ANDROID_LOCATION_METRES")
        val packet = AaCarGps.event(sample, 1_500L)!!
        val body = ProtoReader(packet).message(1)!!
        assertEquals(-5_025, body.int(5))
        // Canonical protobuf int32 -5025 is sign-extended, not truncated to uint32.
        val bytes = packet.toList()
        assertTrue(bytes.contains(0x28.toByte()))
    }

    @Test fun unverifiedAltitudeCannotBeForwardedAsAaAltitude() {
        val sample = car(55.7, 37.6).copy(altitudeMeters = 112.5, altitudeUnit = "UNKNOWN")
        assertFalse(ProtoReader(AaCarGps.event(sample, 1_500L)!!).message(1)!!.has(5))
        assertNotNull(AaCarGps.satellitesEvent(sample, 4_100L))
    }

    @Test fun aaSdpHasPersistentVehicleIdentityAndSatelliteSensor() {
        val packet = AaMessages.serviceDiscoveryResponse(
            1280, 720, 30, 240, true, "Toyota RAV4", true,
            advertiseCarLocation = true, advertiseCarGear = true,
            vehicleId = "FT-persistent-identity-1",
            vehicleMake = "FAW Toyota", vehicleModel = "RAV4", vehicleYear = "2026")
        val meta = ProtoReader(packet)
        assertEquals("FT-persistent-identity-1", meta.string(5))
        assertEquals("FAW Toyota", meta.string(3))
        assertEquals("2026", meta.string(4))
        val huInfo = meta.message(17)!!
        assertEquals("FT-persistent-identity-1", huInfo.string(4))
        assertEquals(1, huInfo.int(9))
        val types = meta.messages(1).first { it.int(1) == AaProtocol.CH_SENSOR }
            .message(2)!!.messages(1).map { it.int(1) }.toSet()
        assertTrue(AaProtocol.SENSOR_LOCATION in types)
        assertTrue(AaProtocol.SENSOR_GPS_SATELLITES in types)
    }

    @Test fun baiduModeMustNotAdvertiseUnusableGps() {
        val response = AaMessages.serviceDiscoveryResponse(1280, 720, 30, 160, true, "RAV4", true)
        val config = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }.message(2)!!
        val types = config.messages(1).map { it.int(1) }.toSet()
        assertFalse(AaProtocol.SENSOR_LOCATION in types)
        assertTrue(AaProtocol.SENSOR_DRIVING_STATUS in types)
    }

    private fun verifiedDr(at: Long, trusted: Boolean = true): DrNavigationFix =
        DrNavigationFix(
            latitude = 55.7, longitude = 37.6,
            accuracyMetres = 12.0, headingDegrees = 90.0,
            fromSelectedGps = GpsSourceMode.CAR,
            producedAtMs = at, estimated = true,
            verifiedVehicleMotion = trusted
        )

    @Test fun noVerifiedDrOrNoAdvertisedCharacterizationNeverMakesUpCoordinates() {
        val gate = AaContinuousDrGate(subscribedWallMs = 10_000L)
        val mode = AaLocationCharacterization.VERIFIED_WHEEL_GYRO_DR
        assertFalse(gate.accept(null, 10_100L, mode))
        assertFalse(gate.accept(verifiedDr(10_101L, false), 10_101L, mode))
        assertFalse(gate.accept(verifiedDr(10_102L), 10_102L,
            AaLocationCharacterization.RAW_GNSS_ONLY))
        assertTrue(gate.accept(verifiedDr(10_103L), 10_103L, mode))
    }

    @Test fun fusedSessionSuppressesEveryRawOemGpsPacketToPreventMultipathLeak() {
        val policy = AaLocationFeedPolicy(
            AaLocationCharacterization.VERIFIED_WHEEL_GYRO_DR, 10_000L)
        val goodOem = car(55.7, 37.6, 10_101L).copy(gpsPacketSequence = 1L)
        val reflectedOem = car(55.7, 37.601, 10_102L).copy(gpsPacketSequence = 2L)
        assertTrue(policy.fusedOnly)
        assertNull(policy.raw(goodOem, 10_101L))
        assertNull(policy.raw(reflectedOem, 10_102L))
        assertNotNull(policy.fused(verifiedDr(10_103L), 10_103L))
        assertNull(policy.raw(goodOem.copy(gpsPacketSequence = 3L), 10_104L))
        assertNotNull(policy.fused(verifiedDr(10_201L), 10_201L))
        assertNull(policy.fused(verifiedDr(10_201L), 10_202L))
    }

    @Test fun unverifiedDrSessionCarriesOnlyFreshOemRawBaseline() {
        val policy = AaLocationFeedPolicy(
            AaLocationCharacterization.RAW_GNSS_ONLY, 10_000L)
        assertFalse(policy.fusedOnly)
        assertNull(policy.fused(verifiedDr(10_101L), 10_101L))
        val fresh = car(55.7, 37.6, 10_101L).copy(gpsPacketSequence = 1L)
        assertNotNull(policy.raw(fresh, 10_101L))
        assertNull(policy.raw(fresh, 10_102L))
        assertNotNull(policy.raw(fresh.copy(gpsPacketSequence = 2L), 10_103L))
    }

    @Test fun frozenSessionCharacterizationForbidsMidSessionUnverifiedSwitch() {
        val policy = AaLocationFeedPolicy(
            AaLocationCharacterization.RAW_GNSS_ONLY, 10_000L)
        assertNull(policy.fused(verifiedDr(10_110L), 10_110L))
        assertNotNull(policy.raw(
            car(55.7, 37.6, 10_120L).copy(gpsPacketSequence = 20L),
            10_120L))
        // A new connection with verified DR requires a NEW SDP. Do not
        // emit one raw and one fused coordinate inside the same AA session.
        val nextSession = AaLocationFeedPolicy(
            AaLocationCharacterization.VERIFIED_WHEEL_GYRO_DR, 20_000L)
        assertNull(nextSession.raw(
            car(55.7, 37.6, 20_100L).copy(gpsPacketSequence = 21L),
            20_100L))
        assertNotNull(nextSession.fused(verifiedDr(20_101L), 20_101L))
    }

}
