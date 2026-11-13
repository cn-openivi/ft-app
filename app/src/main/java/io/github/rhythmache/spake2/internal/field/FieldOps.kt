package io.github.rhythmache.spake2.internal.field

@Suppress("ktlint:standard:property-naming", "ktlint:standard:function-naming")
internal object FieldOps {
    private const val MASK51: Long = 0x7ffffffffffffL

    // bias constants for subtraction (2 * p in 51-bit limbs)
    private const val BIAS0: Long = 0xFFFFFFFFFFFDAL // 2^52 - 38
    private const val BIAS1: Long = 0xFFFFFFFFFFFFEL // 2^52 - 2
    private const val BIAS2: Long = 0xFFFFFFFFFFFFEL
    private const val BIAS3: Long = 0xFFFFFFFFFFFFEL
    private const val BIAS4: Long = 0xFFFFFFFFFFFFEL

    // d = -121665 / 121666 mod (2^255 - 19) in 5x51-bit limbs
    val D: Fe =
        Fe().apply {
            v[0] = 929955233495203L
            v[1] = 466365720129213L
            v[2] = 1662059464998953L
            v[3] = 2033849074728123L
            v[4] = 1442794654840575L
        }

    // sqrt(-1) = 2^((p-1)/4) mod (2^255 - 19) in 5x51-bit limbs
    val SQRTM1: Fe =
        Fe().apply {
            v[0] = 1718705420411056L
            v[1] = 234908883556509L
            v[2] = 2233514472574048L
            v[3] = 2117202627021982L
            v[4] = 765476049583133L
        }

    // Phase B: Constants & Copy
    fun fe0(h: Fe) {
        h.v[0] = 0L
        h.v[1] = 0L
        h.v[2] = 0L
        h.v[3] = 0L
        h.v[4] = 0L
    }

    fun feLoose0(h: FeLoose) {
        h.v[0] = 0L
        h.v[1] = 0L
        h.v[2] = 0L
        h.v[3] = 0L
        h.v[4] = 0L
    }

    fun fe1(h: Fe) {
        h.v[0] = 1L
        h.v[1] = 0L
        h.v[2] = 0L
        h.v[3] = 0L
        h.v[4] = 0L
    }

    fun feLoose1(h: FeLoose) {
        h.v[0] = 1L
        h.v[1] = 0L
        h.v[2] = 0L
        h.v[3] = 0L
        h.v[4] = 0L
    }

    fun feCopy(
        h: Fe,
        f: Fe,
    ) {
        h.v[0] = f.v[0]
        h.v[1] = f.v[1]
        h.v[2] = f.v[2]
        h.v[3] = f.v[3]
        h.v[4] = f.v[4]
    }

    fun feCopyLt(
        h: FeLoose,
        f: Fe,
    ) {
        h.v[0] = f.v[0]
        h.v[1] = f.v[1]
        h.v[2] = f.v[2]
        h.v[3] = f.v[3]
        h.v[4] = f.v[4]
    }

    // Phase C: Add, Sub, Carry
    fun feAdd(
        h: FeLoose,
        f: Fe,
        g: Fe,
    ) {
        h.v[0] = f.v[0] + g.v[0]
        h.v[1] = f.v[1] + g.v[1]
        h.v[2] = f.v[2] + g.v[2]
        h.v[3] = f.v[3] + g.v[3]
        h.v[4] = f.v[4] + g.v[4]
    }

    fun feSub(
        h: FeLoose,
        f: Fe,
        g: Fe,
    ) {
        h.v[0] = (f.v[0] + BIAS0) - g.v[0]
        h.v[1] = (f.v[1] + BIAS1) - g.v[1]
        h.v[2] = (f.v[2] + BIAS2) - g.v[2]
        h.v[3] = (f.v[3] + BIAS3) - g.v[3]
        h.v[4] = (f.v[4] + BIAS4) - g.v[4]
    }

    fun feCarry(
        h: Fe,
        f: FeLoose,
    ) {
        var v0 = f.v[0]
        var v1 = f.v[1]
        var v2 = f.v[2]
        var v3 = f.v[3]
        var v4 = f.v[4]

        var c = v0 ushr 51
        v0 = v0 and MASK51

        v1 += c
        c = v1 ushr 51
        v1 = v1 and MASK51

        v2 += c
        c = v2 ushr 51
        v2 = v2 and MASK51

        v3 += c
        c = v3 ushr 51
        v3 = v3 and MASK51

        v4 += c
        c = v4 ushr 51
        v4 = v4 and MASK51

        v0 += c * 19L
        c = v0 ushr 51
        v0 = v0 and MASK51
        v1 += c

        h.v[0] = v0
        h.v[1] = v1
        h.v[2] = v2
        h.v[3] = v3
        h.v[4] = v4
    }

    // Phase D: FromBytes, ToBytes
    fun feFromBytesStrict(
        h: Fe,
        s: ByteArray,
    ) {
        require(s.size == 32)
        val u0 = s[0].toLong() and 0xFFL
        val u1 = s[1].toLong() and 0xFFL
        val u2 = s[2].toLong() and 0xFFL
        val u3 = s[3].toLong() and 0xFFL
        val u4 = s[4].toLong() and 0xFFL
        val u5 = s[5].toLong() and 0xFFL
        val u6 = s[6].toLong() and 0xFFL
        val u7 = s[7].toLong() and 0xFFL
        val u8 = s[8].toLong() and 0xFFL
        val u9 = s[9].toLong() and 0xFFL
        val u10 = s[10].toLong() and 0xFFL
        val u11 = s[11].toLong() and 0xFFL
        val u12 = s[12].toLong() and 0xFFL
        val u13 = s[13].toLong() and 0xFFL
        val u14 = s[14].toLong() and 0xFFL
        val u15 = s[15].toLong() and 0xFFL
        val u16 = s[16].toLong() and 0xFFL
        val u17 = s[17].toLong() and 0xFFL
        val u18 = s[18].toLong() and 0xFFL
        val u19 = s[19].toLong() and 0xFFL
        val u20 = s[20].toLong() and 0xFFL
        val u21 = s[21].toLong() and 0xFFL
        val u22 = s[22].toLong() and 0xFFL
        val u23 = s[23].toLong() and 0xFFL
        val u24 = s[24].toLong() and 0xFFL
        val u25 = s[25].toLong() and 0xFFL
        val u26 = s[26].toLong() and 0xFFL
        val u27 = s[27].toLong() and 0xFFL
        val u28 = s[28].toLong() and 0xFFL
        val u29 = s[29].toLong() and 0xFFL
        val u30 = s[30].toLong() and 0xFFL
        val u31 = s[31].toLong() and 0x7FL

        h.v[0] = u0 or (u1 shl 8) or (u2 shl 16) or (u3 shl 24) or (u4 shl 32) or (u5 shl 40) or
            ((u6 and 0x07L) shl 48)
        h.v[1] = (u6 ushr 3) or (u7 shl 5) or (u8 shl 13) or (u9 shl 21) or (u10 shl 29) or (u11 shl 37) or
            ((u12 and 0x3FL) shl 45)
        h.v[2] = (u12 ushr 6) or (u13 shl 2) or (u14 shl 10) or (u15 shl 18) or (u16 shl 26) or (u17 shl 34) or
            (u18 shl 42) or ((u19 and 0x01L) shl 50)
        h.v[3] = (u19 ushr 1) or (u20 shl 7) or (u21 shl 15) or (u22 shl 23) or (u23 shl 31) or (u24 shl 39) or
            ((u25 and 0x0FL) shl 47)
        h.v[4] = (u25 ushr 4) or (u26 shl 4) or (u27 shl 12) or (u28 shl 20) or (u29 shl 28) or (u30 shl 36) or
            (u31 shl 44)
    }

    fun feFromBytes(
        h: Fe,
        s: ByteArray,
    ) {
        val copy = s.copyOf(32)
        copy[31] = (copy[31].toInt() and 0x7F).toByte()
        feFromBytesStrict(h, copy)
    }

    fun feToBytes(
        s: ByteArray,
        f: Fe,
    ) {
        require(s.size == 32)
        var v0 = f.v[0]
        var v1 = f.v[1]
        var v2 = f.v[2]
        var v3 = f.v[3]
        var v4 = f.v[4]

        var c = v0 ushr 51
        v0 = v0 and MASK51
        v1 += c
        c = v1 ushr 51
        v1 = v1 and MASK51
        v2 += c
        c = v2 ushr 51
        v2 = v2 and MASK51
        v3 += c
        c = v3 ushr 51
        v3 = v3 and MASK51
        v4 += c
        c = v4 ushr 51
        v4 = v4 and MASK51
        v0 += c * 19L

        c = v0 ushr 51
        v0 = v0 and MASK51
        v1 += c

        var t0 = v0 + 19L
        c = t0 ushr 51
        t0 = t0 and MASK51

        var t1 = v1 + c
        c = t1 ushr 51
        t1 = t1 and MASK51

        var t2 = v2 + c
        c = t2 ushr 51
        t2 = t2 and MASK51

        var t3 = v3 + c
        c = t3 ushr 51
        t3 = t3 and MASK51

        var t4 = v4 + c
        c = t4 ushr 51
        t4 = t4 and MASK51

        val mask = -c
        val r0 = v0 xor ((v0 xor t0) and mask)
        val r1 = v1 xor ((v1 xor t1) and mask)
        val r2 = v2 xor ((v2 xor t2) and mask)
        val r3 = v3 xor ((v3 xor t3) and mask)
        val r4 = v4 xor ((v4 xor t4) and mask)

        s[0] = r0.toByte()
        s[1] = (r0 ushr 8).toByte()
        s[2] = (r0 ushr 16).toByte()
        s[3] = (r0 ushr 24).toByte()
        s[4] = (r0 ushr 32).toByte()
        s[5] = (r0 ushr 40).toByte()
        s[6] = ((r0 ushr 48) or (r1 shl 3)).toByte()

        s[7] = (r1 ushr 5).toByte()
        s[8] = (r1 ushr 13).toByte()
        s[9] = (r1 ushr 21).toByte()
        s[10] = (r1 ushr 29).toByte()
        s[11] = (r1 ushr 37).toByte()
        s[12] = ((r1 ushr 45) or (r2 shl 6)).toByte()

        s[13] = (r2 ushr 2).toByte()
        s[14] = (r2 ushr 10).toByte()
        s[15] = (r2 ushr 18).toByte()
        s[16] = (r2 ushr 26).toByte()
        s[17] = (r2 ushr 34).toByte()
        s[18] = (r2 ushr 42).toByte()
        s[19] = ((r2 ushr 50) or (r3 shl 1)).toByte()

        s[20] = (r3 ushr 7).toByte()
        s[21] = (r3 ushr 15).toByte()
        s[22] = (r3 ushr 23).toByte()
        s[23] = (r3 ushr 31).toByte()
        s[24] = (r3 ushr 39).toByte()
        s[25] = ((r3 ushr 47) or (r4 shl 4)).toByte()

        s[26] = (r4 ushr 4).toByte()
        s[27] = (r4 ushr 12).toByte()
        s[28] = (r4 ushr 20).toByte()
        s[29] = (r4 ushr 28).toByte()
        s[30] = (r4 ushr 36).toByte()
        s[31] = (r4 ushr 44).toByte()
    }

    // Phase E: Field Multiplication
    private fun feMulImpl(
        hv: LongArray,
        fv: LongArray,
        gv: LongArray,
    ) {
        val f0 = fv[0]
        val f1 = fv[1]
        val f2 = fv[2]
        val f3 = fv[3]
        val f4 = fv[4]
        val g0 = gv[0]
        val g1 = gv[1]
        val g2 = gv[2]
        val g3 = gv[3]
        val g4 = gv[4]

        val g1_19 = g1 * 19L
        val g2_19 = g2 * 19L
        val g3_19 = g3 * 19L
        val g4_19 = g4 * 19L

        var accLo = 0L
        var accHi = 0L

        fun addMul(
            a: Long,
            b: Long,
        ) {
            val pLo = a * b
            val pHi = Math.multiplyHigh(a, b)
            val newLo = accLo + pLo
            val carry = if (java.lang.Long.compareUnsigned(newLo, accLo) < 0) 1L else 0L
            accLo = newLo
            accHi = accHi + pHi + carry
        }

        fun addVal(v: Long) {
            val newLo = accLo + v
            val carry = if (java.lang.Long.compareUnsigned(newLo, accLo) < 0) 1L else 0L
            accLo = newLo
            accHi += carry
        }

        fun limb51(): Long = accLo and MASK51

        fun carry51(): Long = (accLo ushr 51) or (accHi shl 13)

        accLo = 0L
        accHi = 0L
        addMul(f0, g0)
        addMul(f1, g4_19)
        addMul(f2, g3_19)
        addMul(f3, g2_19)
        addMul(f4, g1_19)

        val c0 = carry51()
        hv[0] = limb51()

        accLo = 0L
        accHi = 0L
        addVal(c0)
        addMul(f0, g1)
        addMul(f1, g0)
        addMul(f2, g4_19)
        addMul(f3, g3_19)
        addMul(f4, g2_19)

        val c1 = carry51()
        hv[1] = limb51()

        accLo = 0L
        accHi = 0L
        addVal(c1)
        addMul(f0, g2)
        addMul(f1, g1)
        addMul(f2, g0)
        addMul(f3, g4_19)
        addMul(f4, g3_19)

        val c2 = carry51()
        hv[2] = limb51()

        accLo = 0L
        accHi = 0L
        addVal(c2)
        addMul(f0, g3)
        addMul(f1, g2)
        addMul(f2, g1)
        addMul(f3, g0)
        addMul(f4, g4_19)

        val c3 = carry51()
        hv[3] = limb51()

        accLo = 0L
        accHi = 0L
        addVal(c3)
        addMul(f0, g4)
        addMul(f1, g3)
        addMul(f2, g2)
        addMul(f3, g1)
        addMul(f4, g0)

        val c4 = carry51()
        hv[4] = limb51()

        val v0 = hv[0] + c4 * 19L
        val c = v0 ushr 51
        hv[0] = v0 and MASK51
        hv[1] += c
    }

    fun feMul(
        h: Fe,
        f: Fe,
        g: Fe,
    ) = feMulImpl(h.v, f.v, g.v)

    fun feMulTll(
        h: Fe,
        f: FeLoose,
        g: FeLoose,
    ) = feMulImpl(h.v, f.v, g.v)

    fun feMulTlt(
        h: Fe,
        f: FeLoose,
        g: Fe,
    ) = feMulImpl(h.v, f.v, g.v)

    fun feMulTtl(
        h: Fe,
        f: Fe,
        g: FeLoose,
    ) = feMulImpl(h.v, f.v, g.v)

    fun feMulLtt(
        h: FeLoose,
        f: Fe,
        g: Fe,
    ) = feMulImpl(h.v, f.v, g.v)

    // Phase F: Field Squaring
    fun feSquare(
        h: Fe,
        f: Fe,
    ) = feMulImpl(h.v, f.v, f.v)

    fun feSquareTl(
        h: Fe,
        f: FeLoose,
    ) = feMulImpl(h.v, f.v, f.v)

    // feSquare2: h = 2 * f^2
    fun feSquare2(
        h: Fe,
        f: Fe,
    ) {
        feSquare(h, f)
        val tmp = FeLoose()
        feAdd(tmp, h, h)
        feCarry(h, tmp)
    }

    // feCmov: Conditional move (if b == 1, f = g; if b == 0, f unchanged)
    fun feCmov(
        f: FeLoose,
        g: FeLoose,
        b: Long,
    ) {
        val mask = 0L - b
        for (i in 0 until 5) {
            val x = (f.v[i] xor g.v[i]) and mask
            f.v[i] = f.v[i] xor x
        }
    }

    // Phase G: Field Inversion (feInvert)
    fun feInvert(
        out: Fe,
        z: Fe,
    ) {
        val t0 = Fe()
        val t1 = Fe()
        val t2 = Fe()
        val t3 = Fe()

        feSquare(t0, z)
        feSquare(t1, t0)
        feSquare(t1, t1)
        feMul(t1, z, t1)
        feMul(t0, t0, t1)
        feSquare(t2, t0)
        feMul(t1, t1, t2)
        feSquare(t2, t1)
        for (i in 1 until 5) {
            feSquare(t2, t2)
        }
        feMul(t1, t2, t1)
        feSquare(t2, t1)
        for (i in 1 until 10) {
            feSquare(t2, t2)
        }
        feMul(t2, t2, t1)
        feSquare(t3, t2)
        for (i in 1 until 20) {
            feSquare(t3, t3)
        }
        feMul(t2, t3, t2)
        feSquare(t2, t2)
        for (i in 1 until 10) {
            feSquare(t2, t2)
        }
        feMul(t1, t2, t1)
        feSquare(t2, t1)
        for (i in 1 until 50) {
            feSquare(t2, t2)
        }
        feMul(t2, t2, t1)
        feSquare(t3, t2)
        for (i in 1 until 100) {
            feSquare(t3, t3)
        }
        feMul(t2, t3, t2)
        feSquare(t2, t2)
        for (i in 1 until 50) {
            feSquare(t2, t2)
        }
        feMul(t1, t2, t1)
        feSquare(t1, t1)
        for (i in 1 until 5) {
            feSquare(t1, t1)
        }
        feMul(out, t1, t0)
    }

    fun feIsNegative(f: Fe): Int {
        val s = ByteArray(32)
        feToBytes(s, f)
        return s[0].toInt() and 1
    }

    fun feIsNonZero(f: FeLoose): Int {
        val tight = Fe()
        feCarry(tight, f)
        val s = ByteArray(32)
        feToBytes(s, tight)
        for (b in s) {
            if (b != 0.toByte()) return 1
        }
        return 0
    }

    fun feNeg(
        h: FeLoose,
        f: Fe,
    ) {
        val zeroFe = Fe()
        fe0(zeroFe)
        feSub(h, zeroFe, f)
    }

    fun fePow22523(
        out: Fe,
        z: Fe,
    ) {
        val t0 = Fe()
        val t1 = Fe()
        val t2 = Fe()

        feSquare(t0, z)
        feSquare(t1, t0)
        for (i in 1 until 2) {
            feSquare(t1, t1)
        }
        feMul(t1, z, t1)
        feMul(t0, t0, t1)
        feSquare(t0, t0)
        feMul(t0, t1, t0)
        feSquare(t1, t0)
        for (i in 1 until 5) {
            feSquare(t1, t1)
        }
        feMul(t0, t1, t0)
        feSquare(t1, t0)
        for (i in 1 until 10) {
            feSquare(t1, t1)
        }
        feMul(t1, t1, t0)
        feSquare(t2, t1)
        for (i in 1 until 20) {
            feSquare(t2, t2)
        }
        feMul(t1, t2, t1)
        feSquare(t1, t1)
        for (i in 1 until 10) {
            feSquare(t1, t1)
        }
        feMul(t0, t1, t0)
        feSquare(t1, t0)
        for (i in 1 until 50) {
            feSquare(t1, t1)
        }
        feMul(t1, t1, t0)
        feSquare(t2, t1)
        for (i in 1 until 100) {
            feSquare(t2, t2)
        }
        feMul(t1, t2, t1)
        feSquare(t1, t1)
        for (i in 1 until 50) {
            feSquare(t1, t1)
        }
        feMul(t0, t1, t0)
        feSquare(t0, t0)
        for (i in 1 until 2) {
            feSquare(t0, t0)
        }
        feMul(out, t0, z)
    }
}
