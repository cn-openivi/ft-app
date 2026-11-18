package app.ft.carplay

/**
 * Passive wire recognition for the first bytes of a real incoming phone-side iAP2
 * Bluetooth channel. A head unit may send a six-byte link-detect sequence BEFORE
 * any FF 5A framed packet. The existence of either is protocol-level evidence,
 * not MFi authentication or an Apple CarPlay session.
 *
 * The public iAP2 detect sequence was independently cross-checked in upstream
 * open protocol documentation; this is original bounded Kotlin code, not imported
 * CatPlay/Carlinkit implementation. No packets are sent from this observer.
 */
internal class Iap2PhoneWireObserver {
    class Observation(
        val detectSeen: Boolean,
        /** Valid frames are private process memory; never serialize or log payload. */
        val frames: List<Iap2LinkFrameCodec.Frame>
    ) {
        val linkFrameCount: Int get() = frames.size
        override fun toString(): String =
            "Observation(detectSeen=$"+"detectSeen, linkFrameCount=$"+"linkFrameCount)"
    }

    companion object {
        private val DETECT = byteArrayOf(
            0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10
        )
        private const val TAIL_BYTES = 5
    }

    private val link = Iap2LinkStreamParser()
    private var tail = byteArrayOf()

    /** Works even when a Bluetooth socket fragments a six-byte detect sequence. */
    fun accept(bytes: ByteArray): Observation {
        require(bytes.size <= 65_535) { "bounded input only" }
        val joined = tail + bytes
        var detect = false
        if (joined.size >= DETECT.size) {
            for (offset in 0..(joined.size - DETECT.size)) {
                if (DETECT.indices.all { joined[offset + it] == DETECT[it] }) {
                    detect = true
                    break
                }
            }
        }
        tail = joined.takeLast(TAIL_BYTES).toByteArray()
        val frames = link.accept(bytes)
        return Observation(detect, frames)
    }

    fun reset() {
        tail = byteArrayOf()
        link.reset()
    }
}
