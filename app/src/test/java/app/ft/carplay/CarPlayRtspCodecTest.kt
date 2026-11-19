package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayRtspCodecTest {
    private val c = CarPlayRtspCodec
    private fun b(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun ok(s: String, seq: Int) =
        c.parseReply(b(s), seq) as CarPlayRtspCodec.Parse.Accepted

    @Test fun requestIsSequentialAndCannotInjectHeaders() {
        assertEquals("GET /info RTSP/1.0\r\nCSeq: 7\r\nUser-Agent: OpenIVI-FT200\r\n\r\n",
            String(c.request("GET", "/info", 7)!!, Charsets.US_ASCII))
        assertNull(c.request("CONNECT", "/info", 1))
        assertNull(c.request("GET", "http://peer", 1))
        assertNull(c.request("GET", "/../config", 1))
        assertNull(c.request("GET", "/info\r\nEvil: 1", 1))
    }

    @Test fun parsesFragmentedResponseAndPreservesExactSequence() {
        val bytes = b("RTSP/1.0 200 OK\r\nCSeq: 42\r\nContent-Length: 4\r\n\r\nTEST")
        assertTrue(c.parseReply(bytes.copyOfRange(0, bytes.size-2),42)
            is CarPlayRtspCodec.Parse.NeedMore)
        val parsed = c.parseReply(bytes,42) as CarPlayRtspCodec.Parse.Accepted
        assertEquals(200,parsed.reply.status)
        assertEquals(bytes.size,parsed.consumed)
        assertArrayEquals(b("TEST"),parsed.reply.body)
        assertFalse(parsed.reply.toString().contains("TEST"))
    }

    @Test fun missingWrongDuplicateCseqFailClosed() {
        assertEquals(CarPlayRtspCodec.Reject.MISSING_OR_WRONG_CSEQ,
            (c.parseReply(b("RTSP/1.0 200 OK\r\n\r\n"),7)
                as CarPlayRtspCodec.Parse.Rejected).reason)
        assertEquals(CarPlayRtspCodec.Reject.MISSING_OR_WRONG_CSEQ,
            (c.parseReply(b("RTSP/1.0 200 OK\r\nCSeq: 8\r\n\r\n"),7)
                as CarPlayRtspCodec.Parse.Rejected).reason)
        assertEquals(CarPlayRtspCodec.Reject.MALFORMED_HEADERS,
            (c.parseReply(b("RTSP/1.0 200 OK\r\nCSeq: 7\r\ncseq: 7\r\n\r\n"),7)
                as CarPlayRtspCodec.Parse.Rejected).reason)
    }

    @Test fun status401AndWrongProtocolNeverBecomeAuthenticated() {
        assertFalse(ok("RTSP/1.0 401 Unauthorized\r\nCSeq: 4\r\n\r\n",4).reply.success)
        assertTrue(c.parseReply(b("HTTP/1.1 200 OK\r\nCSeq: 4\r\n\r\n"),4)
            is CarPlayRtspCodec.Parse.Rejected)
    }

    @Test fun chunksDecodeWithoutLeakingLengthLines() {
        val raw = "RTSP/1.0 200 OK\r\nCSeq: 9\r\nTransfer-Encoding: chunked\r\n\r\n"+
            "4\r\nTest\r\n3\r\n123\r\n0\r\n\r\n"
        assertArrayEquals(b("Test123"),ok(raw,9).reply.body)
        assertTrue(c.parseReply(b(raw).copyOfRange(0,b(raw).size-1),9)
            is CarPlayRtspCodec.Parse.NeedMore)
    }

    @Test fun conflictingOrUnboundedBodyHeadersAreRejected() {
        val both = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nTransfer-Encoding: chunked\r\n"+
            "Content-Length: 4\r\n\r\n0\r\n\r\n"
        assertEquals(CarPlayRtspCodec.Reject.CONFLICTING_BODY_FRAMING,
            (c.parseReply(b(both),1) as CarPlayRtspCodec.Parse.Rejected).reason)
        val huge = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 999999999\r\n\r\n"
        assertEquals(CarPlayRtspCodec.Reject.UNBOUNDED_BODY,
            (c.parseReply(b(huge),1) as CarPlayRtspCodec.Parse.Rejected).reason)
    }

    @Test fun malformedChunkAndHeaderControlCharsAreRejected() {
        val chunk = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nTransfer-Encoding: chunked\r\n\r\n"+
            "4\r\nTESTx\r\n0\r\n\r\n"
        assertTrue(c.parseReply(b(chunk),1) is CarPlayRtspCodec.Parse.Rejected)
        val header = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nX-Evil: a\u0001b\r\n\r\n"
        assertTrue(c.parseReply(b(header),1) is CarPlayRtspCodec.Parse.Rejected)
    }

    @Test fun unsolicitedTrailingResponseBytesFailClosedRatherThanDisappear() {
        val one = b("RTSP/1.0 200 OK\r\nCSeq: 10\r\nContent-Length: 3\r\n\r\nONE")
        val next = b("RTSP/1.0 200 OK\r\nCSeq: 11\r\n\r\n")
        assertTrue(c.parseSingleReply(one, 10) is CarPlayRtspCodec.Parse.Accepted)
        assertEquals(CarPlayRtspCodec.Reject.UNSOLICITED_TRAILING_BYTES,
            (c.parseSingleReply(one + next, 10) as CarPlayRtspCodec.Parse.Rejected).reason)
        assertTrue(c.parseSingleReply(one.copyOf(one.size - 1), 10)
            is CarPlayRtspCodec.Parse.NeedMore)
    }

    @Test fun bodyHasExplicitBoundedContentType() {
        val body = b("SECRET")
        val request = c.request("POST","/feedback",1,body,"application/octet-stream")!!
        assertTrue(String(request,Charsets.US_ASCII).contains("Content-Length: 6\r\n"))
        assertNull(c.request("POST","/feedback",1,body,"a\r\nEvil: 1"))
    }
}
