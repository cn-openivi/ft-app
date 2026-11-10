package app.ft.aa

import app.ft.core.ProtoReader
import app.ft.core.CarProtocol
import app.ft.core.GpsSourceMode
import org.junit.Assert.*
import org.junit.Test

class AaSensorAdvertisementTest {
    private fun sensors(advertise: Boolean): List<Int> {
        val response = AaMessages.serviceDiscoveryResponse(
            1920, 1008, 30, 160, false, "XA60",
            advertiseCarLocation = advertise)
        val sensorChannel = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }
        return sensorChannel.message(2)!!.messages(1).map { it.int(1) }
    }

    @Test fun explicitPhoneGpsDisablesOemLocationWithoutDroppingRealGear() {
        val advertise = GpsSourceMode.PHONE.advertisesCarLocation(CarProtocol.ICCOA)
        assertFalse(advertise)
        val response = AaMessages.serviceDiscoveryResponse(
            1920, 1008, 30, 240, false, "XA60",
            advertiseCarLocation = advertise,
            advertiseCarGear = true
        )
        val sensor = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }.message(2)!!
        val offered = sensor.messages(1).map { it.int(1) }
        assertFalse(offered.contains(AaProtocol.SENSOR_LOCATION))
        assertTrue(offered.contains(AaProtocol.SENSOR_GEAR))
        assertFalse(sensor.has(2)) // No false RAW_GPS_ONLY descriptor.
    }

    @Test fun carGpsIsAdvertisedFirstWithoutDroppingOtherSensors() {
        assertEquals(listOf(AaProtocol.SENSOR_LOCATION, AaProtocol.SENSOR_GPS_SATELLITES,
            AaProtocol.SENSOR_DRIVING_STATUS, AaProtocol.SENSOR_NIGHT_MODE), sensors(true))
    }

    private fun advertisedDpi(width: Int, height: Int, requestedDpi: Int): Int {
        val response = AaMessages.serviceDiscoveryResponse(
            width, height, 30, requestedDpi, false, "XA60")
        val video = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_VIDEO }
        return video.message(3)!!.message(4)!!.int(5)
    }

    @Test fun largerIconsAreNegotiatedThroughVideoDpiNotCropping() {
        assertEquals(240, advertisedDpi(1920, 1008, 240))
        assertEquals(240, advertisedDpi(1280, 720, 240))
        assertEquals(170, advertisedDpi(800, 480, 240))
    }

    @Test fun iccoaAdvertisesRealGearWithoutAnyDiagnosticOptIn() {
        val response = AaMessages.serviceDiscoveryResponse(
            1920, 1008, 30, 240, false, "XA60",
            advertiseCarLocation = true, advertiseCarGear = true)
        val sensor = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }.message(2)!!
        // Field 2 is a GPS capability bitmask, not a refresh interval.
        assertEquals(256, sensor.int(2)) // RAW_GPS_ONLY (no dead reckoning)
        assertTrue(sensor.has(2))
        val types = sensor.messages(1).map { it.int(1) }
        assertEquals(listOf(AaProtocol.SENSOR_LOCATION, AaProtocol.SENSOR_GPS_SATELLITES,
            AaProtocol.SENSOR_GEAR, AaProtocol.SENSOR_DRIVING_STATUS, AaProtocol.SENSOR_NIGHT_MODE), types)
        assertFalse(types.contains(3)) // OEM speed unit unverified, never advertise CAR_SPEED
        assertFalse(types.contains(7)) // No verified parking-brake sensor
    }

    @Test fun noCarLocationIsAdvertisedInOtherProtocols() {
        assertEquals(listOf(AaProtocol.SENSOR_DRIVING_STATUS,
            AaProtocol.SENSOR_NIGHT_MODE), sensors(false))
        val response = AaMessages.serviceDiscoveryResponse(
            1920, 1008, 30, 160, false, "XA60",
            advertiseCarLocation = false)
        val config = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }.message(2)!!
        assertFalse(config.has(2)) // No unsupported OEM GPS characterization
    }

    @Test fun empiricallyWorkingFt172NavDescriptorIsExactAndSurvivesGpsAdvertisement() {
        // FT172 Samsung AA17.9 accepted NavigationType=2 and opened NAVIGATION.
        // FT173 regressed to 1 and its sessions closed before NAVIGATION open.
        // Keep this observed SDP wire intact while adjusting sensor GPS encoding.
        for (offerGps in listOf(false, true)) {
            for (offerAudio in listOf(false, true)) {
                val response = AaMessages.serviceDiscoveryResponse(
                    1920, 1008, 30, 240, offerAudio, "XA60",
                    advertiseCarLocation = offerGps,
                    advertiseCarGear = true
                )
                val nav = ProtoReader(response).messages(1)
                    .single { it.int(1) == AaProtocol.CH_NAVIGATION }
                assertArrayEquals(
                    byteArrayOf(8, -24, 7, 16, 2),
                    nav.bytes(8)
                )
                assertEquals(2, nav.message(8)!!.int(2))
                assertEquals(1000, nav.message(8)!!.int(1))
            }
        }
    }

    @Test fun aaLocationSubscriptionWireIsPhoneInitiatedAndNotAnSdpHint() {
        // AA SensorRequest(LOCATION=1, interval=1000 ms): message 0x8001,
        // phone -> FT. FT only replies after receiving this real request.
        val requestBody = byteArrayOf(8, 1, 16, -24, 7)
        assertEquals(AaProtocol.SENSOR_LOCATION, AaMessages.sensorRequestType(requestBody))
        assertEquals(1_000L, ProtoReader(requestBody).long(2))
        assertArrayEquals(byteArrayOf(8, 0), AaMessages.sensorStartResponse())
        assertArrayEquals(byteArrayOf(8, 1, 16, 2), AaCarGps.sensorError(2))
    }

    @Test fun locationCharacterizationIsNotRefreshIntervalOrInventedFusion() {
        val response = AaMessages.serviceDiscoveryResponse(
            1280, 720, 30, 160, true, "XA60",
            advertiseCarLocation = true)
        val config = ProtoReader(response).messages(1)
            .first { it.int(1) == AaProtocol.CH_SENSOR }.message(2)!!
        val rawGpsOnly = config.int(2)
        assertEquals(0x100, rawGpsOnly)
        assertEquals(0, rawGpsOnly and 0xFF) // no advertised inertial / fused flags
        assertNotEquals(1000, rawGpsOnly)
    }
}
