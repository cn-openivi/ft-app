package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2ReliableLinkWindowTest {
    private fun link(next: Int = 0x9e, remote: Int = 0x42) =
        Iap2LinkNegotiator.ConfirmedLink(
            sessions = listOf(Iap2LinkNegotiator.Session(7, 0, 2)),
            nextPhoneSequence = next,
            lastAccessorySequence = remote
        )

    private fun inbound(
        seq: Int = 0x43, ack: Int = 0,
        data: ByteArray = byteArrayOf(1, 2),
        session: Int = 7
    ) = Iap2LinkFrameCodec.Frame(0x40, seq, ack, session, data)

    @Test fun sendUsesConfirmedSequenceAndSessionWithoutInventingPeerIdentity() {
        val state = Iap2ReliableLinkWindow(link())
        val proposal = state.prepareData(7, byteArrayOf(0x40, 0x40, 0, 6, 0x1d, 0))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.READY, proposal.status)
        val packet = Iap2LinkFrameCodec.decode(proposal.wire!!)!!
        assertEquals(0x9e, packet.sequence)
        assertEquals(0x42, packet.acknowledgement)
        assertEquals(7, packet.session)
        assertEquals(1, state.pendingCount())
    }

    @Test fun duplicateAndGapAreNeverDeliveredAsFreshData() {
        val state = Iap2ReliableLinkWindow(link())
        assertEquals(Iap2ReliableLinkWindow.Status.ACCEPTED_IN_ORDER,
            state.observe(inbound(seq = 0x43)).status)
        val duplicate = state.observe(inbound(seq = 0x43))
        assertEquals(Iap2ReliableLinkWindow.Status.DUPLICATE, duplicate.status)
        assertNull(duplicate.payload)
        val missing = state.observe(inbound(seq = 0x45))
        assertEquals(Iap2ReliableLinkWindow.Status.OUT_OF_ORDER, missing.status)
        assertNull(missing.payload)
        val next = state.observe(inbound(seq = 0x44))
        assertEquals(Iap2ReliableLinkWindow.Status.ACCEPTED_IN_ORDER, next.status)
        assertTrue(next.payload!!.contentEquals(byteArrayOf(1, 2)))
    }

    @Test fun peerAckClearsOnlyExactOutstandingSequence() {
        val state = Iap2ReliableLinkWindow(link())
        assertEquals(Iap2ReliableLinkWindow.SendStatus.READY,
            state.prepareData(7, byteArrayOf(1)).status)
        assertFalse(state.observe(inbound(seq = 0x43, ack = 0x9d,
            data = byteArrayOf())).ackOfOutstanding)
        assertEquals(1, state.pendingCount())
        val real = state.observe(inbound(seq = 0x43, ack = 0x9e,
            data = byteArrayOf()))
        assertEquals(Iap2ReliableLinkWindow.Status.EMPTY_ACK, real.status)
        assertTrue(real.ackOfOutstanding)
        assertEquals(0, state.pendingCount())
        val second = state.prepareData(7, byteArrayOf(2))
        assertEquals(0x9f, Iap2LinkFrameCodec.decode(second.wire!!)!!.sequence)
    }

    @Test fun boundedRetriesReproduceExactlySameFrameWithoutIncrementingSequence() {
        val state = Iap2ReliableLinkWindow(link(), maxRetries = 2)
        val initial = state.prepareData(7, byteArrayOf(3, 4)).wire!!
        val first = state.retryOutstanding()
        val second = state.retryOutstanding()
        assertEquals(Iap2ReliableLinkWindow.SendStatus.RETRY, first.status)
        assertEquals(Iap2ReliableLinkWindow.SendStatus.RETRY, second.status)
        assertTrue(initial.contentEquals(first.wire))
        assertTrue(initial.contentEquals(second.wire))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.RETRY_EXHAUSTED,
            state.retryOutstanding().status)
        assertEquals(1, state.pendingCount())
    }

    @Test fun wraparoundFrom255To0IsStillAnInOrderPacket() {
        val state = Iap2ReliableLinkWindow(link(next = 255, remote = 255))
        assertEquals(Iap2ReliableLinkWindow.Status.ACCEPTED_IN_ORDER,
            state.observe(inbound(seq = 0)).status)
        val initial = state.prepareData(7, byteArrayOf(1))
        assertEquals(255, Iap2LinkFrameCodec.decode(initial.wire!!)!!.sequence)
        assertTrue(state.observe(inbound(seq = 0, ack = 255,
            data = byteArrayOf())).ackOfOutstanding)
        assertEquals(0, Iap2LinkFrameCodec.decode(
            state.prepareData(7, byteArrayOf(2)).wire!!)!!.sequence)
    }

    @Test fun noUnlimitedOutstandingOrCrossSessionPackets() {
        val state = Iap2ReliableLinkWindow(link())
        assertEquals(Iap2ReliableLinkWindow.SendStatus.INVALID_SESSION,
            state.prepareData(8, byteArrayOf(1)).status)
        assertEquals(Iap2ReliableLinkWindow.SendStatus.INVALID_FRAME,
            state.prepareData(7, byteArrayOf()).status)
        assertEquals(Iap2ReliableLinkWindow.SendStatus.READY,
            state.prepareData(7, byteArrayOf(1)).status)
        assertEquals(Iap2ReliableLinkWindow.SendStatus.BUSY,
            state.prepareData(7, byteArrayOf(2)).status)
        val unknown = state.observe(inbound(session = 8))
        assertEquals(Iap2ReliableLinkWindow.Status.UNKNOWN_SESSION, unknown.status)
        assertNull(unknown.payload)
    }

    @Test fun resetAndEmptyAckDoNotFakeIncomingControlMessages() {
        val state = Iap2ReliableLinkWindow(link())
        assertEquals(Iap2ReliableLinkWindow.SendStatus.NOTHING_PENDING,
            state.retryOutstanding().status)
        val withoutData = state.observe(inbound(data = byteArrayOf()))
        assertEquals(Iap2ReliableLinkWindow.Status.EMPTY_ACK, withoutData.status)
        assertNull(withoutData.payload)
        state.prepareData(7, byteArrayOf(1))
        state.reset()
        assertEquals(0, state.pendingCount())
        assertEquals(0x9e,
            Iap2LinkFrameCodec.decode(state.prepareData(7, byteArrayOf(2)).wire!!)!!.sequence)
    }

    @Test fun pureAckOnlyForInOrderDuplicatesAndGapsNeverForAckOnly() {
        val state = Iap2ReliableLinkWindow(link())
        val first = state.observe(inbound(seq = 0x43))
        val ack = state.prepareCumulativeAck(first)
        assertEquals(Iap2ReliableLinkWindow.SendStatus.ACK_READY, ack.status)
        val wire = Iap2LinkFrameCodec.decode(ack.wire!!)!!
        assertEquals(0x40, wire.control)
        assertEquals(0, wire.session)
        assertEquals(0x9d, wire.sequence)
        assertEquals(0x43, wire.acknowledgement)
        assertTrue(wire.payload.isEmpty())
        assertEquals(0, state.pendingCount())

        val duplicate = state.prepareCumulativeAck(state.observe(inbound(seq = 0x43)))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.ACK_READY, duplicate.status)
        val gap = state.prepareCumulativeAck(state.observe(inbound(seq = 0x45)))
        assertEquals(0x43, Iap2LinkFrameCodec.decode(gap.wire!!)!!.acknowledgement)

        val empty = state.prepareCumulativeAck(state.observe(inbound(data = byteArrayOf())))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.ACK_NOT_REQUIRED, empty.status)
    }

    @Test fun rejectedFramesCannotTriggerAcksOrClearOurRetransmit() {
        val state = Iap2ReliableLinkWindow(link())
        state.prepareData(7, byteArrayOf(1))
        val foreign = state.observe(inbound(seq = 0x43, ack = 0x9e, session = 8))
        assertEquals(Iap2ReliableLinkWindow.SendStatus.ACK_NOT_REQUIRED,
            state.prepareCumulativeAck(foreign).status)
        assertEquals(1, state.pendingCount())
        val eak = state.observe(Iap2LinkFrameCodec.Frame(
            0x60, 0x43, 0x9e, 7, byteArrayOf(4)))
        assertEquals(Iap2ReliableLinkWindow.Status.MALFORMED_HEADER, eak.status)
        assertEquals(1, state.pendingCount())
    }

    @Test fun pureAckDoesNotConsumeNextDataSequence() {
        val state = Iap2ReliableLinkWindow(link())
        state.prepareCumulativeAck(state.observe(inbound(seq = 0x43)))
        val outbound = state.prepareData(7, byteArrayOf(1))
        assertEquals(0x9e, Iap2LinkFrameCodec.decode(outbound.wire!!)!!.sequence)
        assertEquals(0x9e, Iap2LinkFrameCodec.decode(
            state.retryOutstanding().wire!!)!!.sequence)
    }

    @Test fun invalidHeaderNumbersFailWithoutAcknowledgingOutstanding() {
        val state = Iap2ReliableLinkWindow(link())
        assertNotNull(state.prepareData(7, byteArrayOf(1)).wire)
        val bad = state.observe(Iap2LinkFrameCodec.Frame(
            control = 0x40, sequence = 256, acknowledgement = 0x9e,
            session = 7, payload = byteArrayOf()))
        assertEquals(Iap2ReliableLinkWindow.Status.MALFORMED_HEADER, bad.status)
        assertFalse(bad.ackOfOutstanding)
        assertEquals(1, state.pendingCount())
    }
}
