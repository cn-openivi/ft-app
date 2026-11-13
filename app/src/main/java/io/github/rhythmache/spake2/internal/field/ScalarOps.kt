package io.github.rhythmache.spake2.internal.field

import java.math.BigInteger

@Suppress("ktlint:standard:property-naming")
internal object ScalarOps {
    // Group order L = 2^252 + 27742317777372353535851937790883648493
    val L: BigInteger = BigInteger.TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")

    /**
     * Equivalent of BoringSSL's x25519_sc_reduce:
     * Reduces a 64 byte little endian integer modulo group order L, returning 32 little-endian bytes.
     */
    fun scReduce(
        out32: ByteArray,
        in64: ByteArray,
    ) {
        require(in64.size == 64) { "in64 must be 64 bytes" }
        require(out32.size == 32) { "out32 must be 32 bytes" }

        val bi = BigInteger(1, in64.reversedArray()).mod(L)
        val mag = bi.toByteArray()
        val start = if (mag.size > 32) mag.size - 32 else 0
        val len = minOf(mag.size, 32)
        val temp = ByteArray(32)
        System.arraycopy(mag, start, temp, 32 - len, len)
        val le = temp.reversedArray()
        System.arraycopy(le, 0, out32, 0, 32)
    }

    /**
     * multiply scalar by 8 (left shift by 3) to clear low cofactor bits.
     */
    fun scalarMul8(
        out32: ByteArray,
        in32: ByteArray,
    ) {
        require(in32.size == 32)
        require(out32.size == 32)
        val bi = BigInteger(1, in32.reversedArray()).shiftLeft(3).mod(BigInteger.TWO.pow(256))
        val mag = bi.toByteArray()
        val start = if (mag.size > 32) mag.size - 32 else 0
        val len = minOf(mag.size, 32)
        val temp = ByteArray(32)
        System.arraycopy(mag, start, temp, 32 - len, len)
        val le = temp.reversedArray()
        System.arraycopy(le, 0, out32, 0, 32)
    }

    /**
     * apply password scalar cofactor hack (matching BoringSSL SPAKE2_generate_msg).
     */
    fun applyPasswordScalarCofactorHack(
        out32: ByteArray,
        reducedPw32: ByteArray,
    ) {
        require(reducedPw32.size == 32)
        require(out32.size == 32)

        var scalar = BigInteger(1, reducedPw32.reversedArray())
        var order = L

        if (scalar.testBit(0)) scalar = scalar.add(order)
        order = order.shiftLeft(1)
        if (scalar.testBit(1)) scalar = scalar.add(order)
        order = order.shiftLeft(1)
        if (scalar.testBit(2)) scalar = scalar.add(order)

        val mag = scalar.toByteArray()
        val start = if (mag.size > 32) mag.size - 32 else 0
        val len = minOf(mag.size, 32)
        val temp = ByteArray(32)
        System.arraycopy(mag, start, temp, 32 - len, len)
        val le = temp.reversedArray()
        System.arraycopy(le, 0, out32, 0, 32)
    }
}
