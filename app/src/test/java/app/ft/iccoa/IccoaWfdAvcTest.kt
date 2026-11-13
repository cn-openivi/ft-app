package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaWfdAvcTest {
    @Test fun selects1080OnlyWhenReceiverAnnouncesCea1080AndLevelFour() {
        val body = "wfd_video_formats: 28 00 01 04 000000a0 00000000 00000000 00 0000 0000 00 none none\\r\\n"
            .replace("\\r\\n", "\r\n")
        val choice = IccoaWfdAvc.choose(body, 60)!!
        assertEquals(1920, choice.width)
        assertEquals(1080, choice.height)
        assertEquals(30, choice.fps)
        assertEquals(4, choice.level)
        assertEquals(7, choice.ceaMode)
        assertTrue(IccoaWfdAvc.m4(choice).contains("00000080"))
        val config = IccoaRtsp.capabilities(IccoaVideoConfig("127.0.0.1", 2422, 1272, 60), body)
        assertEquals(choice, config.wfdAvc)
        assertEquals(1920, IccoaVideoPlan.from(config).streamWidth)
    }

    @Test fun fallsBackTo720WhenSinkOnlyOffers720Level31() {
        val body = "wfd_video_formats: 28 00 01 01 00000020 00000000 00000000 00 0000 0000 00 none none"
        val choice = IccoaWfdAvc.choose(body, 30)!!
        assertEquals(1280, choice.width)
        assertEquals(720, choice.height)
        assertEquals(1, choice.level)
        val plan = IccoaVideoPlan.from(IccoaVideoConfig("127.0.0.1", 2422, 1272, 30, wfdAvc = choice))
        assertEquals(1280, plan.streamWidth)
        assertEquals(720, plan.streamHeight)
        assertEquals(3_555_555, plan.bitrate)
        assertTrue(plan.preserveAspect)
    }

    @Test fun malformedWfdOrUnsupportedDecoderCannotImplyCapabilities() {
        val no1080 = "wfd_video_formats: 28 00 01 01 00000080 00000000 00000000 00 0000 0000 00 none none"
        assertNull(IccoaWfdAvc.choose(no1080, 30))
        val highOnly = "wfd_video_formats: 28 00 02 04 00000080 00000000 00000000 00 0000 0000 00 none none"
        assertNull(IccoaWfdAvc.choose(highOnly, 30))
        assertNull(IccoaWfdAvc.choose("wfd_video_formats: none", 30))
        assertNull(IccoaWfdAvc.choose("wfd_video_formats: 28 00 01 04 000000a0", 30))
    }
}
