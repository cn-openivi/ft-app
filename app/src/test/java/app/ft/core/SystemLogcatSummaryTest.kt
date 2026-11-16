package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class SystemLogcatSummaryTest {
    @Test fun noRawCoordinatesIdentifiersOrThirdPartyMessageLeavesDiagnosticArchive() {
        val input = listOf(
            "11-15 19:04:50.123  181  242 E MediaCodec: CodecException obsolete surface " +
                "GPS=55.748912,37.617993 email=driver@example.com",
            "11-15 19:04:50.124  181  242 W AudioRecord: ERROR_BAD_VALUE " +
                "phone=+79990001122",
            "11-15 19:04:50.125  141  242 E UntrustedNavigationApp: 55.748912 " +
                "user private text",
            "java.lang.IllegalStateException: /sdcard/secret/location.json"
        ).joinToString("\n")
        val safe = SystemLogcatSummary.summarize(input)
        assertTrue(safe.contains("tag=MediaCodec event=OBSOLETE_SURFACE"))
        assertTrue(safe.contains("tag=AudioRecord event=AUDIO_BAD_VALUE"))
        assertTrue(safe.contains("matched_events=2"))
        listOf("55.748912", "37.617993", "driver@example.com", "+79990001122",
            "UntrustedNavigationApp", "/sdcard", "private text").forEach {
            assertFalse("raw message escaped scrubbing: $it", safe.contains(it))
        }
    }

    @Test fun malformedHeaderAndStackTraceCannotInjectArbitraryContent() {
        val raw = "11-15 19:04:50.120  111  222 E MediaCodec: codec ready\n" +
            "android.secret: longitude=12.3456789\n" +
            "11-15 19:04:51.321  333  444 E AndroidRuntime: FATAL EXCEPTION: main"
        val safe = SystemLogcatSummary.summarize(raw)
        assertEquals(2, safe.lineSequence().count { it.contains(" event=") })
        assertTrue(safe.contains("event=FATAL_EXCEPTION"))
        assertFalse(safe.contains("longitude"))
    }
}
