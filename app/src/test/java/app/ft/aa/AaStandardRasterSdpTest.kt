package app.ft.aa

import app.ft.core.ProtoReader
import org.junit.Assert.*
import org.junit.Test

class AaStandardRasterSdpTest {
    private fun check(w: Int, h: Int): Pair<Int, Int> {
        val sdp = AaMessages.serviceDiscoveryResponse(w, h, 30, 240, true, "OpenIVI")
        val channels = ProtoReader(sdp).messages(1)
        val video = channels.first { it.int(1) == AaProtocol.CH_VIDEO }
            .message(3)!!.message(4)!!
        val input = channels.first { it.int(1) == AaProtocol.CH_INPUT }
            .message(4)!!.message(2)!!
        val expected = AaProtocol.standardSize(w, h)
        val resolution = when (expected.first) {
            1920 -> AaProtocol.RES_1920x1080
            1280 -> AaProtocol.RES_1280x720
            else -> AaProtocol.RES_800x480
        }
        assertEquals(resolution, video.int(1))
        assertEquals(0, video.int(3)) // width margins
        assertEquals(0, video.int(4)) // no fake 72px vertical margin
        assertEquals(expected.first, input.int(1))
        assertEquals(expected.second, input.int(2))
        return expected
    }

    @Test fun old1920x1008AdvertisesFullHdWithoutMargins() {
        assertEquals(1920 to 1080, check(1920, 1008))
    }

    @Test fun normalSdHdFullHdStayExact() {
        assertEquals(800 to 480, check(800, 480))
        assertEquals(1280 to 720, check(1280, 720))
        assertEquals(1920 to 1080, check(1920, 1080))
    }

    @Test fun fullHdKeepsRequested240DpiWithZeroPadding() {
        val sdp = AaMessages.serviceDiscoveryResponse(1920, 1008, 30,
            240, true, "OpenIVI")
        val video = ProtoReader(sdp).messages(1)
            .first { it.int(1) == AaProtocol.CH_VIDEO }.message(3)!!.message(4)!!
        assertEquals(240, video.int(5))
        assertEquals(0, video.int(3))
        assertEquals(0, video.int(4))
    }

    @Test fun nonstandardCarAndManualValuesCannotLeakToWire() {
        assertEquals(1920 to 1080, check(2422, 1272))
        assertEquals(1920 to 1080, check(1600, 900))
        assertEquals(1280 to 720, check(1138, 640))
    }
}
