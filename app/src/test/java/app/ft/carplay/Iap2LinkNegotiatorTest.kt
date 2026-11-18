package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LinkNegotiatorTest {
    private fun syn(controlSessionId: Int = 3) = Iap2LinkFrameCodec.Frame(
        control = 0x80, sequence = 0x41, acknowledgement = 0, session = 0,
        payload = byteArrayOf(
            1, 3, 0x10, 0, 0, 0xc8.toByte(), 0, 0x64, 5, 1,
            controlSessionId.toByte(), 0, 1, 5, 2, 1
        )
    )

    @Test fun realAccessorySynIsAcknowledgedWithExactSequenceAndItsOwnSessions() {
        val n = Iap2LinkNegotiator()
        val result = n.onRealLinkFrame(syn())
        assertEquals(Iap2LinkNegotiator.Phase.AWAITING_ACCESSORY_ACK, result.phase)
        val output = Iap2LinkFrameCodec.decode(result.reply!!)!!
        assertEquals(0xc0, output.control)
        assertEquals(0x41, output.acknowledgement)
        assertEquals(0, output.session)
        assertEquals(3, n.sessions.first { it.type == 0 }.id)
        assertEquals(2, n.sessions.size)

        // The peer must send a real ACK; merely preparing SYN+ACK is not success.
        assertFalse(n.phase == Iap2LinkNegotiator.Phase.LINK_NEGOTIATED)
        val ack = Iap2LinkFrameCodec.Frame(0x40, 0x41, output.sequence, 0, byteArrayOf())
        assertEquals(Iap2LinkNegotiator.Phase.LINK_NEGOTIATED, n.onRealLinkFrame(ack).phase)
    }

    @Test fun invalidSynAndSpoofedAckAreNeverCountedAsNegotiated() {
        val n = Iap2LinkNegotiator()
        val wrong = Iap2LinkFrameCodec.Frame(0x40, 0, 0, 0, byteArrayOf())
        assertEquals(Iap2LinkNegotiator.Failure.NOT_SYN, n.onRealLinkFrame(wrong).failure)
        assertNull(n.onRealLinkFrame(syn()).reply)
        n.reset()
        val outbound = n.onRealLinkFrame(syn()).reply
        assertNotNull(outbound)
        val wrongAck = Iap2LinkFrameCodec.Frame(0x40, 0, 0, 0, byteArrayOf())
        assertEquals(Iap2LinkNegotiator.Failure.WRONG_ACK, n.onRealLinkFrame(wrongAck).failure)
        assertEquals(Iap2LinkNegotiator.Phase.REJECTED, n.phase)
    }

    @Test fun malformedSessionTableAndUnsupportedVersionAreRejected() {
        val n = Iap2LinkNegotiator()
        val noControl = syn().copy(payload = syn().payload.clone().apply { this[11] = 2 })
        assertEquals(Iap2LinkNegotiator.Failure.MISSING_CONTROL_SESSION,
            n.onRealLinkFrame(noControl).failure)

        n.reset()
        assertEquals(Iap2LinkNegotiator.Failure.UNSUPPORTED_SYN,
            n.onRealLinkFrame(syn().copy(payload = byteArrayOf(2, 3, 0, 64, 0, 50, 0, 50, 5, 1, 1, 0, 1))).failure)
    }

    @Test fun accessoryMayAssignDifferentRealControlSessionNumber() {
        val n = Iap2LinkNegotiator()
        assertNotNull(n.onRealLinkFrame(syn(7)).reply)
        assertEquals(7, n.sessions.first { it.type == 0 }.id)
    }

    @Test fun resetInvalidatesPreviouslyNegotiatedEvidence() {
        val n = Iap2LinkNegotiator()
        val reply = Iap2LinkFrameCodec.decode(n.onRealLinkFrame(syn()).reply!!)!!
        n.onRealLinkFrame(Iap2LinkFrameCodec.Frame(0x40, 0x41, reply.sequence, 0, byteArrayOf()))
        assertEquals(Iap2LinkNegotiator.Phase.LINK_NEGOTIATED, n.phase)
        n.reset()
        assertEquals(Iap2LinkNegotiator.Phase.AWAITING_ACCESSORY_SYN, n.phase)
        assertTrue(n.sessions.isEmpty())
    }
}
