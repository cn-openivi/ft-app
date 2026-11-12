package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class IccoaDirectAvcTest {
    /** Deterministic AVC Baseline Level 4.0 SPS fixture, no MediaCodec dependency. */
    private fun sps1080(): ByteArray {
        class Bits {
            val bits = ArrayList<Int>()
            fun put(value: Int, width: Int) { for (i in width - 1 downTo 0) bits.add(value ushr i and 1) }
            fun ue(v: Int) {
                val n = v + 1
                val size = 32 - Integer.numberOfLeadingZeros(n)
                repeat(size - 1) { bits.add(0) }
                put(n, size)
            }
            fun bytes(): ByteArray {
                bits.add(1)
                while (bits.size % 8 != 0) bits.add(0)
                return ByteArray(bits.size / 8) { i ->
                    var v = 0
                    repeat(8) { j -> v = (v shl 1) or bits[i * 8 + j] }
                    v.toByte()
                }
            }
        }
        val b = Bits()
        b.put(66, 8) // Baseline
        b.put(0, 8)  // constraint flags
        b.put(40, 8) // Level 4.0
        b.ue(0)      // SPS id
        b.ue(0)      // log2_max_frame_num_minus4
        b.ue(0)      // pic_order_cnt_type
        b.ue(0)      // log2_max_pic_order_cnt_lsb_minus4
        b.ue(1)      // max refs
        b.put(0, 1)  // gaps_in_frame_num
        b.ue(119)    // 120 * 16 = 1920
        b.ue(67)     // 68 * 16 = 1088
        b.put(1, 1)  // frame_mbs_only
        b.put(1, 1)  // direct_8x8
        b.put(1, 1)  // cropping present
        b.ue(0); b.ue(0); b.ue(0); b.ue(4) // crop bottom 8 -> 1080
        b.put(0, 1)  // no VUI
        return byteArrayOf(0x67.toByte()) + b.bytes()
    }

    private fun annex(vararg nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nal) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(n)
        }
        return out.toByteArray()
    }

    @Test fun parsesCroppedAvcBaselineSpsAndRejectsInvalidBuffer() {
        val format = IccoaDirectAvc.sps(annex(sps1080(), byteArrayOf(0x68, 0)))!!
        assertEquals(1920, format.width)
        assertEquals(1080, format.height)
        assertEquals(66, format.profileIdc)
        assertEquals(40, format.levelIdc)
        assertNull(IccoaDirectAvc.sps(byteArrayOf(0, 0, 0, 1, 0x67)))
        assertNull(IccoaDirectAvc.sps(byteArrayOf()))
    }

    @Test fun acceptsAvccLengthPrefixSpsAndDistinguishesIdrFromPFrame() {
        val sps = sps1080()
        val prefix = byteArrayOf(0, 0, (sps.size ushr 8).toByte(), sps.size.toByte())
        assertEquals(1920, IccoaDirectAvc.sps(prefix + sps)!!.width)
        assertTrue(IccoaDirectAvc.isIdr(annex(byteArrayOf(0x09, 0x10), byteArrayOf(0x65, 0x44))))
        assertFalse(IccoaDirectAvc.isIdr(annex(byteArrayOf(0x41, 0x22))))
        assertTrue(IccoaDirectAvc.isIdr(byteArrayOf(0, 0, 0, 2, 0x65, 0x11)))
    }

    @Test fun directRtpPtsUsesMonotonicHostClockEvenIfSamplesRepeatOrRewind() {
        var now = 1_000_000L
        val clock = IccoaDirectClock { now }
        assertEquals(1_000_000L, clock.next())
        assertEquals(1_000_001L, clock.next())
        now = 999_000
        assertEquals(1_000_002L, clock.next())
        now = 1_040_000
        assertEquals(1_040_000L, clock.next())
        clock.reset()
        assertEquals(1_040_000L, clock.next())
    }
}
