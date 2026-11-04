package app.ft.aa

import java.nio.ByteBuffer

/**
 * SSLEngine.UNWRAP BUFFER_UNDERFLOW means the unfinished TLS record must
 * be carried into the next encrypted AA frame. The previous AaCrypto
 * discarded these bytes, making arbitrary transport segmentation fatal.
 *
 * Stateless between sessions: each AaCrypto owns exactly one buffer.
 */
internal class AaTlsCiphertextCarry(private val maxPending: Int = 65_536) {
    private var pending = ByteArray(0)

    fun next(incoming: ByteArray): ByteBuffer {
        require(pending.size.toLong() + incoming.size <= maxPending.toLong()) {
            "oversized pending AA TLS record"
        }
        val bytes = if (pending.isEmpty()) incoming else pending + incoming
        pending = ByteArray(0)
        return ByteBuffer.wrap(bytes)
    }

    fun retain(buffer: ByteBuffer) {
        require(buffer.remaining() in 0..maxPending)
        pending = ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    fun pendingBytes(): Int = pending.size
}
