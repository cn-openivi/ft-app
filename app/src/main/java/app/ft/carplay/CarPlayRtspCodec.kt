package app.ft.carplay

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Bounded RTSP/1.0 control framing. This handles bytes; it does NOT perform
 * AirPlay pairing, provide stream encryption keys or assert FAW interoperability.
 * Every outgoing request is sequential and bound to an explicitly matched CSeq.
 * Never log headers, response bodies, passwords or peer identifiers.
 */
internal object CarPlayRtspCodec {
    private const val MAX_HEADER = 8192
    private const val MAX_BODY = 1_048_576
    private val CRLF = byteArrayOf(13, 10)
    private val END = byteArrayOf(13, 10, 13, 10)

    enum class Reject {
        INVALID_STATUS, MALFORMED_HEADERS, MISSING_OR_WRONG_CSEQ,
        UNBOUNDED_BODY, CONFLICTING_BODY_FRAMING, MALFORMED_CHUNKS
    }
    class Reply(val status: Int, val cSeq: Int, val body: ByteArray) {
        val success: Boolean get() = status in 200..299
        override fun toString(): String = "RtspReply(status=" + status +
            ", cseq=" + cSeq + ", bodyBytes=" + body.size + ", redacted)"
    }
    sealed class Parse {
        object NeedMore : Parse()
        data class Accepted(val reply: Reply, val consumed: Int) : Parse()
        data class Rejected(val reason: Reject) : Parse()
    }

    fun request(method: String, path: String, cSeq: Int,
                body: ByteArray = byteArrayOf(),
                contentType: String? = null): ByteArray? {
        if (method !in setOf("GET", "POST", "SETUP", "RECORD", "TEARDOWN", "OPTIONS"))
            return null
        if (!path.startsWith("/") || path.length > 512 ||
            path.contains("..") || path.any { it.code < 33 || it.code > 126 } ||
            cSeq !in 1..Int.MAX_VALUE || body.size > MAX_BODY) return null
        if (body.isNotEmpty() && (contentType == null || !safeHeader(contentType))) return null
        if (body.isEmpty() && contentType != null) return null
        val header = buildString {
            append(method).append(' ').append(path).append(" RTSP/1.0\r\n")
            append("CSeq: ").append(cSeq).append("\r\n")
            append("User-Agent: OpenIVI-FT200\r\n")
            if (body.isNotEmpty()) {
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
            }
            append("\r\n")
        }
        if (header.length > MAX_HEADER) return null
        return header.toByteArray(StandardCharsets.US_ASCII) + body.copyOf()
    }

    fun parseReply(bytes: ByteArray, expectedCSeq: Int): Parse {
        if (bytes.size < 4) return Parse.NeedMore
        val end = find(bytes, END, 0)
        if (end < 0)
            return if (bytes.size > MAX_HEADER) Parse.Rejected(Reject.MALFORMED_HEADERS)
                else Parse.NeedMore
        val from = end + 4
        if (from > MAX_HEADER) return Parse.Rejected(Reject.MALFORMED_HEADERS)
        if (expectedCSeq < 1) return Parse.Rejected(Reject.MISSING_OR_WRONG_CSEQ)
        if ((0 until from).any {
            val c = bytes[it].toInt() and 255
            (c < 32 && c != 13 && c != 10) || c > 126
        }) return Parse.Rejected(Reject.MALFORMED_HEADERS)
        val lines = String(bytes, 0, end, StandardCharsets.US_ASCII).split("\r\n")
        if (lines.isEmpty() ||
            !Regex("RTSP/1\\.0 [1-5][0-9][0-9](?: [^\\r\\n]*)?").matches(lines[0]))
            return Parse.Rejected(Reject.INVALID_STATUS)
        val status = lines[0].substring(9, 12).toIntOrNull()
            ?: return Parse.Rejected(Reject.INVALID_STATUS)
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val split = line.indexOf(':')
            if (split <= 0 || line[0].isWhitespace()) return Parse.Rejected(Reject.MALFORMED_HEADERS)
            val key = line.substring(0, split).trim().lowercase(Locale.ROOT)
            val value = line.substring(split + 1).trim()
            if (!Regex("[a-z0-9-]+").matches(key) || !safeHeader(value) ||
                headers.putIfAbsent(key, value) != null)
                return Parse.Rejected(Reject.MALFORMED_HEADERS)
        }
        val number = headers["cseq"]?.toIntOrNull()
        if (number != expectedCSeq) return Parse.Rejected(Reject.MISSING_OR_WRONG_CSEQ)
        val len = headers["content-length"]
        val te = headers["transfer-encoding"]
        if (len != null && te != null) return Parse.Rejected(Reject.CONFLICTING_BODY_FRAMING)
        if (te != null) {
            if (!te.equals("chunked", ignoreCase = true))
                return Parse.Rejected(Reject.CONFLICTING_BODY_FRAMING)
            return chunks(bytes, from, status, number)
        }
        val n = len?.toLongOrNull() ?: if (len == null) 0L else -1L
        if (n !in 0L..MAX_BODY.toLong()) return Parse.Rejected(Reject.UNBOUNDED_BODY)
        if (bytes.size - from < n) return Parse.NeedMore
        val total = from + n.toInt()
        return Parse.Accepted(Reply(status, number, bytes.copyOfRange(from, total)), total)
    }

    private fun chunks(bytes: ByteArray, start: Int, status: Int, cSeq: Int): Parse {
        var pos = start
        val out = ByteArrayOutputStream()
        repeat(65536) {
            val end = find(bytes, CRLF, pos)
            if (end < 0) return if (bytes.size - pos > 32)
                Parse.Rejected(Reject.MALFORMED_CHUNKS) else Parse.NeedMore
            if (end - pos !in 1..8) return Parse.Rejected(Reject.MALFORMED_CHUNKS)
            val raw = String(bytes, pos, end - pos, StandardCharsets.US_ASCII)
            if (!raw.matches(Regex("[0-9A-Fa-f]{1,8}")))
                return Parse.Rejected(Reject.MALFORMED_CHUNKS)
            val size = raw.toLongOrNull(16) ?: return Parse.Rejected(Reject.MALFORMED_CHUNKS)
            pos = end + 2
            if (size > MAX_BODY - out.size().toLong())
                return Parse.Rejected(Reject.UNBOUNDED_BODY)
            if (size == 0L) {
                if (bytes.size - pos < 2) return Parse.NeedMore
                if (bytes[pos] != 13.toByte() || bytes[pos + 1] != 10.toByte())
                    return Parse.Rejected(Reject.MALFORMED_CHUNKS)
                return Parse.Accepted(Reply(status, cSeq, out.toByteArray()), pos + 2)
            }
            val after = pos.toLong() + size + 2L
            if (after > bytes.size) return Parse.NeedMore
            val endData = pos + size.toInt()
            if (bytes[endData] != 13.toByte() || bytes[endData + 1] != 10.toByte())
                return Parse.Rejected(Reject.MALFORMED_CHUNKS)
            out.write(bytes, pos, size.toInt())
            pos = endData + 2
        }
        return Parse.Rejected(Reject.MALFORMED_CHUNKS)
    }

    private fun safeHeader(value: String): Boolean =
        value.length in 1..128 && value.all { it.code in 32..126 } &&
        !value.contains('\r') && !value.contains('\n')

    private fun find(bytes: ByteArray, pattern: ByteArray, from: Int): Int {
        if (from < 0) return -1
        for (at in from..(bytes.size - pattern.size))
            if (pattern.indices.all { bytes[at + it] == pattern[it] }) return at
        return -1
    }
}
