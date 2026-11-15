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
    @Test fun ownerFullHdPreferenceIsAdmittedOnlyWithExplicitSinkModeAndEncoderSupport() {
        val both = "wfd_video_formats: 28 00 01 04 000000a0 00000000 00000000 00 0000 0000 00 none none"
        val attempt = IccoaWfdAvc.negotiate(both, 30, 1920 to 1080) { true }
        assertEquals(1920 to 1080, attempt.selected?.let { it.width to it.height })
        assertEquals("REQUESTED_RASTER_COMMON_CODEC", attempt.reason)
        assertEquals(2, attempt.advertised.size)
    }

    @Test fun unsupported1080EncoderFallsBackToActuallyAdvertised720NotFakeFullHd() {
        val both = "wfd_video_formats: 28 00 01 04 000000a0 00000000 00000000 00 0000 0000 00 none none"
        val attempt = IccoaWfdAvc.negotiate(both, 30, 1920 to 1080) {
            it.width == 1280 && it.height == 720
        }
        assertEquals("REQUESTED_RASTER_NOT_SUPPORTED_BY_SOURCE_ENCODER", attempt.reason)
        assertEquals(1280 to 720, attempt.selected?.let { it.width to it.height })
        assertEquals(1, attempt.selected?.level)
    }

    @Test fun sinkAdvertisesOnly720SoFullHdDirectIsNotNegotiable() {
        val only720 = "wfd_video_formats: 28 00 01 01 00000020 00000000 00000000 00 0000 0000 00 none none"
        val attempt = IccoaWfdAvc.negotiate(only720, 30, 1920 to 1080) { true }
        assertEquals("REQUESTED_RASTER_NOT_ADVERTISED_BY_SINK", attempt.reason)
        assertEquals(1280 to 720, attempt.selected?.let { it.width to it.height })
        // The existing AA selection logic preserves the manually selected
        // Full HD and reports composited fallback, never a false Direct PASS.
        val plan = IccoaVideoPlan.from(IccoaVideoConfig(
            "127.0.0.1", 2422, 1272, 30,
            wfdAvc = attempt.selected, wfdNegotiationReason = attempt.reason
        ))
        val aa = IccoaAaVideoProfile.choose(plan, true, false, 1920, 1080)
        assertFalse(aa.directStandard)
        assertFalse(aa.applyToPrefs)
        assertEquals(1920 to 1080, aa.width to aa.height)
    }

    @Test fun sinkDecoderHasNotProvenAnyH264CommonCodec() {
        val only720 = "wfd_video_formats: 28 00 01 01 00000020 00000000 00000000 00 0000 0000 00 none none"
        val attempt = IccoaWfdAvc.negotiate(only720, 30, 1920 to 1080) { false }
        assertNull(attempt.selected)
        assertFalse(attempt.advertised.isEmpty())
    }

}
