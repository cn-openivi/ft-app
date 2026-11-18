package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2SynPayloadParserTest {
    private val parser = Iap2SynPayloadParser

    private fun header(version: Int): ByteArray = byteArrayOf(
        version.toByte(), 3, 0x10, 0, 0, 0xc8.toByte(),
        0, 0x64, 5, 1
    )

    private val firstControl = byteArrayOf(7, 0, 2)
    private val secondMedia = byteArrayOf(5, 2, 1)

    @Test fun versionOneParsesEntriesDirectlyAfterTenByteHeader() {
        val syn = parser.parse(header(1) + firstControl + secondMedia)
        assertNotNull(syn)
        assertEquals(1, syn!!.linkVersion)
        assertEquals(2, syn.sessions.size)
        assertEquals(7, syn.sessions[0].id)
        assertEquals(0, syn.sessions[0].type)
        assertEquals(2, syn.sessions[0].version)
    }

    @Test fun versionTwoParsesExplicitCountAndExtensions() {
        val noExtensions = header(2) + byteArrayOf(0, 2) +
            firstControl + secondMedia
        val a = parser.parse(noExtensions)
        assertNotNull(a)
        assertEquals(7, a!!.sessions[0].id)

        val extended = header(2) + byteArrayOf(2, 0x11, 0x22, 2) +
            firstControl + secondMedia
        val b = parser.parse(extended)
        assertNotNull(b)
        assertEquals(2, b!!.linkVersion)
        assertEquals(5, b.sessions[1].id)
    }

    @Test fun validVersionThreeIsAlsoParsedFromExtensionLayout() {
        val syn = parser.parse(header(3) + byteArrayOf(0, 1) + firstControl)
        assertNotNull(syn)
        assertEquals(3, syn!!.linkVersion)
        assertEquals(1, syn.sessions.size)
    }

    @Test fun missingOrForgedExtensionCountNeverBecomesSessionData() {
        assertNull(parser.parse(header(2) + firstControl))
        assertNull(parser.parse(header(2) + byteArrayOf(6, 1, 2, 3, 4, 5, 6, 1) +
            firstControl))
        assertNull(parser.parse(header(2) + byteArrayOf(2, 0x11, 0x22, 2) +
            firstControl))
        assertNull(parser.parse(header(2) + byteArrayOf(0, 0) +
            firstControl))
    }

    @Test fun duplicateIdAndInvalidTypeAndBadPayloadAreRejected() {
        assertNull(parser.parse(header(1) + firstControl + byteArrayOf(7, 2, 1)))
        assertNull(parser.parse(header(1) + byteArrayOf(8, 5, 2)))
        assertNull(parser.parse(header(1) + byteArrayOf(8, 0x80.toByte(), 2)))
        assertNull(parser.parse(header(1) + byteArrayOf(0, 0, 2)))
        assertNull(parser.parse(header(1) + firstControl + byteArrayOf(8)))
        assertNull(parser.parse(header(0) + firstControl))
    }

    @Test fun invalidSynchronizedReliabilityParametersFailClosed() {
        val noOutstanding = header(1).also { it[1] = 0 } + firstControl
        val noRetrans = header(1).also { it[8] = 0 } + firstControl
        val tooShortAck = header(1).also { it[4] = 0; it[5] = 10 } + firstControl
        assertNull(parser.parse(noOutstanding))
        assertNull(parser.parse(noRetrans))
        assertNull(parser.parse(tooShortAck))
        assertTrue(parser.parse(header(1) + firstControl) != null)
    }

    @Test fun negotiatorUsesActuallyNegotiatedVersionTwoControlSession() {
        val n = Iap2LinkNegotiator()
        val linkSyn = Iap2LinkFrameCodec.Frame(
            control = 0x80,
            sequence = 0x41,
            acknowledgement = 0,
            session = 0,
            payload = header(2) + byteArrayOf(0, 2) +
                firstControl + secondMedia
        )
        val step = n.onRealLinkFrame(linkSyn)
        assertEquals(Iap2LinkNegotiator.Phase.AWAITING_ACCESSORY_ACK, step.phase)
        val transmitted = Iap2LinkFrameCodec.decode(step.reply!!)!!
        assertEquals(0xc0, transmitted.control)
        assertEquals(0x41, transmitted.acknowledgement)
        assertTrue(transmitted.payload.contentEquals(linkSyn.payload))
        assertNull(n.confirmedLink())
        assertEquals(Iap2LinkNegotiator.Phase.LINK_NEGOTIATED,
            n.onRealLinkFrame(Iap2LinkFrameCodec.Frame(
                control = 0x40, sequence = 0x42,
                acknowledgement = transmitted.sequence, session = 0,
                payload = byteArrayOf()
            )).phase)
        assertEquals(7, n.confirmedLink()!!.sessions.single { it.type == 0 }.id)
        assertEquals(2, n.confirmedLink()!!.sessions.single { it.type == 0 }.version)
    }
}
