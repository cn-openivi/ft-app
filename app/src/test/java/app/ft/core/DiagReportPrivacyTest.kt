package app.ft.core

import app.ft.aa.AaProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagReportPrivacyTest {
    @Test fun oldWifiPasswordHexDumpIsRemovedInAnyExportMode() {
        val raw = "18:30 D AA-BT: TX bt type=${AaProtocol.BT_WIFI_INFO_RESPONSE}  0A 04 50 53 4B 31"
        val out = DiagReportPrivacy.scrub(raw)
        assertTrue(out.contains("TX bt type=3 payload=REDACTED_WIFI_PASSWORD"))
        assertFalse(out.contains("50 53 4B 31"))
        assertEquals(out, DiagReportPrivacy.scrub(out))
    }

    @Test fun unrelatedBluetoothPacketsStillRetainRawDetailWhenAuthorized() {
        val raw = "18:30 D AA-BT: TX bt type=${AaProtocol.BT_WIFI_VERSION_RESPONSE}  0A 04 01 02"
        assertEquals(raw, DiagReportPrivacy.scrub(raw))
    }

    @Test fun masksFullColonAndHyphenMacButKeepsStableSuffix() {
        val raw = "BT device 12:34:56:78:9a:bc reconnect 12-34-56-78-9A-BC"
        val out = DiagReportPrivacy.scrub(raw)
        assertFalse(out.contains("12:34:56:78:9a:bc"))
        assertFalse(out.contains("12-34-56-78-9A-BC"))
        assertEquals(2, Regex("xx:xx:xx:xx:9A:BC").findAll(out).count())
        assertEquals(out, DiagReportPrivacy.scrub(out))
    }

    @Test fun removesSongAndArtistButRetainsCoverByteCountAndTimestamp() {
        val raw = "18:30 I CarLife: told the car the song: 'My secret song' by Private Singer, with cover art (2048 bytes)"
        val out = DiagReportPrivacy.scrub(raw)
        assertTrue(out.startsWith("18:30 I CarLife:"))
        assertTrue(out.contains("[title/artist omitted], with cover art (2048 bytes)"))
        assertFalse(out.contains("My secret song"))
        assertFalse(out.contains("Private Singer"))
    }

    @Test fun quotedAndUnicodeSongTitlesCannotEscapeExportRedaction() {
        val raw = "18:30 I CarLife: told the car the song: 'It's музыка' by Другой"
        val out = DiagReportPrivacy.scrub(raw)
        assertFalse(out.contains("музыка"))
        assertFalse(out.contains("Другой"))
        assertTrue(out.contains("[title/artist omitted]"))
    }

    @Test fun multilineArchivesAndUnrelatedEventsAreUnaffected() {
        val raw = "I ICCOA: DIRECT_VIDEO_POST_PAUSE_RECOVERED elapsedMs=120\n" +
            "I CarLife: told the car the song: 'A' by B\n" +
            "I ICCOA: RTP keyframes=2"
        val out = DiagReportPrivacy.scrub(raw)
        assertTrue(out.contains("DIRECT_VIDEO_POST_PAUSE_RECOVERED elapsedMs=120"))
        assertTrue(out.contains("RTP keyframes=2"))
        assertFalse(out.contains("'A' by B"))
        assertEquals(2, out.count { it == '\n' })
    }

    @Test fun unrelatedGpsAndProtocolEvidenceIsNotModified() {
        val raw = "18:30 I ICCOA: speed=60 gear=2 gpsSampleCount=20 packetBytes=1024"
        assertEquals(raw, DiagReportPrivacy.scrub(raw))
    }
}
