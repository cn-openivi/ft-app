package app.ft.iccoa

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** TCP does not respect UTF-8 character boundaries. Keep trailing incomplete
 * code-point bytes across reads rather than decoding each packet with U+FFFD.
 */
internal class IccoaUtf8Frames {
    private var tail = byteArrayOf()

    fun accept(chunk: ByteArray): String {
        if (chunk.isEmpty()) return ""
        val payload = tail + chunk
        val input = ByteBuffer.wrap(payload)
        val chars = CharBuffer.allocate(payload.size.coerceAtLeast(1))
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(input, chars, false)
        if (result.isError) result.throwException()
        check(!result.isOverflow) { "UIBC UTF-8 decoder unexpectedly overflowed" }
        tail = ByteArray(input.remaining()).also { input.get(it) }
        require(tail.size <= 3) { "UIBC UTF-8 incomplete sequence too long" }
        chars.flip()
        return chars.toString()
    }

    fun finish() {
        if (tail.isNotEmpty()) throw EOFException("Incomplete UIBC UTF-8 sequence")
    }
}
