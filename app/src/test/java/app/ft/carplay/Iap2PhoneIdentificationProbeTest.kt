package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2PhoneIdentificationProbeTest {
    private fun confirmed(controlVersion: Int = 2): Iap2LinkNegotiator.ConfirmedLink {
        val negotiator = Iap2LinkNegotiator(senderInitialSequence = 0x9d)
        assertNull(negotiator.confirmedLink())
        val syn = Iap2LinkFrameCodec.Frame(
            0x80, 0x41, 0, 0,
            byteArrayOf(
                1, 3, 0x10, 0, 0, 0xc8.toByte(), 0, 0x64, 5, 1,
                7, 0, controlVersion.toByte(), 4, 2, 1
            )
        )
        val synack = negotiator.onRealLinkFrame(syn).reply!!
        assertNull(negotiator.confirmedLink())
        val reply = Iap2LinkFrameCodec.decode(synack)!!
        val ack = Iap2LinkFrameCodec.Frame(0x40, 0x42, reply.sequence, 0, byteArrayOf())
        assertEquals(Iap2LinkNegotiator.Phase.LINK_NEGOTIATED,
            negotiator.onRealLinkFrame(ack).phase)
        return negotiator.confirmedLink()!!
    }

    @Test fun noLinkOrOptInNeverCreatesOutboundBytes() {
        val gate = Iap2PhoneIdentificationProbe()
        assertEquals(Iap2PhoneIdentificationProbe.Result.DIAGNOSTIC_DISABLED,
            gate.prepare(confirmed(), false).result)
        assertEquals(Iap2PhoneIdentificationProbe.Result.LINK_NOT_CONFIRMED,
            gate.prepare(null, true).result)
        assertEquals(Iap2PhoneIdentificationProbe.Result.REQUEST_PREPARED,
            gate.prepare(confirmed(), true).result)
    }

    @Test fun realVersionTwoControlAndPeerSequenceAreUsedWithoutInventingSession() {
        val gate = Iap2PhoneIdentificationProbe()
        val link = confirmed(2)
        val proposal = gate.prepare(link, true)
        assertEquals(Iap2PhoneIdentificationProbe.Result.REQUEST_PREPARED, proposal.result)
        assertEquals(7, proposal.controlSessionId)
        assertEquals(2, proposal.negotiatedVersion)
        val frame = Iap2LinkFrameCodec.decode(proposal.frame!!)!!
        assertEquals(0x40, frame.control)
        assertEquals(7, frame.session)
        assertEquals(0x9e, frame.sequence)
        assertEquals(0x42, frame.acknowledgement)
        val request = Iap2ControlSessionCodec.decode(frame.payload)!!
        assertEquals(0x1D00, request.id)
        assertTrue(request.parameters.isEmpty())
        assertEquals(6, frame.payload.size)
    }

    @Test fun unexpectedVersionIsExplicitlyUnsupportedNotCoercedToTwo() {
        val gate = Iap2PhoneIdentificationProbe()
        val step = gate.prepare(confirmed(1), true)
        assertEquals(Iap2PhoneIdentificationProbe.Result.UNSUPPORTED_CONTROL_VERSION, step.result)
        assertEquals(1, step.negotiatedVersion)
        assertNull(step.frame)
    }

    @Test fun controlSessionsAreUniqueAndOneRequestPerPhysicalSession() {
        val gate = Iap2PhoneIdentificationProbe()
        val link = confirmed()
        assertNotNull(gate.prepare(link, true).frame)
        assertEquals(Iap2PhoneIdentificationProbe.Result.REQUEST_ALREADY_PREPARED,
            gate.prepare(link, true).result)
        gate.reset()
        assertNotNull(gate.prepare(link, true).frame)
        val ambiguous = link.copy(sessions = listOf(
            Iap2LinkNegotiator.Session(3, 0, 2),
            Iap2LinkNegotiator.Session(4, 0, 2)
        ))
        gate.reset()
        assertEquals(Iap2PhoneIdentificationProbe.Result.MISSING_OR_AMBIGUOUS_CONTROL,
            gate.prepare(ambiguous, true).result)
    }

    @Test fun malformedLinkMetadataFailsWithoutSending() {
        val original = confirmed()
        val gate = Iap2PhoneIdentificationProbe()
        assertEquals(Iap2PhoneIdentificationProbe.Result.LINK_NOT_CONFIRMED,
            gate.prepare(original.copy(lastAccessorySequence = -1), true).result)
        assertEquals(Iap2PhoneIdentificationProbe.Result.REQUEST_PREPARED,
            gate.prepare(original, true).result)
        assertFalse(Iap2ControlSessionCodec.decode(
            Iap2LinkFrameCodec.decode(gate.prepare(original, true).frame
                ?: byteArrayOf())?.payload ?: byteArrayOf()
        )?.id == 0x4301)
    }
}
