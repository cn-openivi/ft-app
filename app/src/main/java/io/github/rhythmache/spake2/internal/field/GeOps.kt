package io.github.rhythmache.spake2.internal.field

@Suppress("ktlint:standard:property-naming", "ktlint:standard:function-naming")
internal object GeOps {
    // 2 * d mod (2^255 - 19) in 5x51-bit limbs
    val D2: Fe =
        Fe().apply {
            v[0] = 1859910466990425L // 0x69BCD2F431959L
            v[1] = 932731440258426L // 0x3508499B4957AL
            v[2] = 1072319116312658L // 0x3CF62A9008852L
            v[3] = 1815898335770999L // 0x673998DCA5177L
            v[4] = 633789495995903L // 0x2408665B171FFL
        }

    fun geP2_0(h: GeP2) {
        FieldOps.fe0(h.X)
        FieldOps.fe1(h.Y)
        FieldOps.fe1(h.Z)
    }

    fun geP3_0(h: GeP3) {
        FieldOps.fe0(h.X)
        FieldOps.fe1(h.Y)
        FieldOps.fe1(h.Z)
        FieldOps.fe0(h.T)
    }

    fun geCached_0(h: GeCached) {
        FieldOps.feLoose1(h.YplusX)
        FieldOps.feLoose1(h.YminusX)
        FieldOps.feLoose1(h.Z)
        FieldOps.feLoose0(h.T2d)
    }

    fun geP3ToP2(
        r: GeP2,
        p: GeP3,
    ) {
        FieldOps.feCopy(r.X, p.X)
        FieldOps.feCopy(r.Y, p.Y)
        FieldOps.feCopy(r.Z, p.Z)
    }

    fun geP1P1ToCached(
        r: GeCached,
        p: GeP1P1,
    ) {
        val t = GeP3()
        geP1P1ToP3(t, p)
        geP3ToCached(r, t)
    }

    fun geP2Dbl(
        r: GeP1P1,
        p: GeP2,
    ) {
        val trX = Fe()
        val trZ = Fe()
        val trT = Fe()
        val t0 = Fe()

        FieldOps.feSquare(trX, p.X)
        FieldOps.feSquare(trZ, p.Y)
        FieldOps.feSquare2(trT, p.Z)
        FieldOps.feAdd(r.Y, p.X, p.Y)
        FieldOps.feSquareTl(t0, r.Y)

        FieldOps.feAdd(r.Y, trZ, trX)
        FieldOps.feSub(r.Z, trZ, trX)
        FieldOps.feCarry(trZ, r.Y)
        FieldOps.feSub(r.X, t0, trZ)
        FieldOps.feCarry(trZ, r.Z)
        FieldOps.feSub(r.T, trT, trZ)
    }

    fun cmovCached(
        t: GeCached,
        u: GeCached,
        b: Long,
    ) {
        FieldOps.feCmov(t.YplusX, u.YplusX, b)
        FieldOps.feCmov(t.YminusX, u.YminusX, b)
        FieldOps.feCmov(t.Z, u.Z, b)
        FieldOps.feCmov(t.T2d, u.T2d, b)
    }

    fun geP3ToCached(
        r: GeCached,
        p: GeP3,
    ) {
        FieldOps.feAdd(r.YplusX, p.Y, p.X)
        FieldOps.feSub(r.YminusX, p.Y, p.X)
        FieldOps.feCopyLt(r.Z, p.Z)
        FieldOps.feMulLtt(r.T2d, p.T, D2)
    }

    fun geP1P1ToP2(
        r: GeP2,
        p: GeP1P1,
    ) {
        FieldOps.feMulTll(r.X, p.X, p.T)
        FieldOps.feMulTll(r.Y, p.Y, p.Z)
        FieldOps.feMulTll(r.Z, p.Z, p.T)
    }

    fun geP1P1ToP3(
        r: GeP3,
        p: GeP1P1,
    ) {
        FieldOps.feMulTll(r.X, p.X, p.T)
        FieldOps.feMulTll(r.Y, p.Y, p.Z)
        FieldOps.feMulTll(r.Z, p.Z, p.T)
        FieldOps.feMulTll(r.T, p.X, p.Y)
    }

    fun geAdd(
        r: GeP1P1,
        p: GeP3,
        q: GeCached,
    ) {
        val trX = Fe()
        val trY = Fe()
        val trZ = Fe()
        val trT = Fe()

        FieldOps.feAdd(r.X, p.Y, p.X)
        FieldOps.feSub(r.Y, p.Y, p.X)
        FieldOps.feMulTll(trZ, r.X, q.YplusX)
        FieldOps.feMulTll(trY, r.Y, q.YminusX)
        FieldOps.feMulTlt(trT, q.T2d, p.T)
        FieldOps.feMulTtl(trX, p.Z, q.Z)
        FieldOps.feAdd(r.T, trX, trX)
        FieldOps.feSub(r.X, trZ, trY)
        FieldOps.feAdd(r.Y, trZ, trY)
        FieldOps.feCarry(trZ, r.T)
        FieldOps.feAdd(r.Z, trZ, trT)
        FieldOps.feSub(r.T, trZ, trT)
    }

    fun geSub(
        r: GeP1P1,
        p: GeP3,
        q: GeCached,
    ) {
        val trX = Fe()
        val trY = Fe()
        val trZ = Fe()
        val trT = Fe()

        FieldOps.feAdd(r.X, p.Y, p.X)
        FieldOps.feSub(r.Y, p.Y, p.X)
        FieldOps.feMulTll(trZ, r.X, q.YminusX)
        FieldOps.feMulTll(trY, r.Y, q.YplusX)
        FieldOps.feMulTlt(trT, q.T2d, p.T)
        FieldOps.feMulTtl(trX, p.Z, q.Z)
        FieldOps.feAdd(r.T, trX, trX)
        FieldOps.feSub(r.X, trZ, trY)
        FieldOps.feAdd(r.Y, trZ, trY)
        FieldOps.feCarry(trZ, r.T)
        FieldOps.feSub(r.Z, trZ, trT)
        FieldOps.feAdd(r.T, trZ, trT)
    }

    fun geToBytes(
        s: ByteArray,
        h: GeP2,
    ) {
        require(s.size == 32)
        val recip = Fe()
        val x = Fe()
        val y = Fe()

        FieldOps.feInvert(recip, h.Z)
        FieldOps.feMul(x, h.X, recip)
        FieldOps.feMul(y, h.Y, recip)
        FieldOps.feToBytes(s, y)
        s[31] = (s[31].toInt() xor (FieldOps.feIsNegative(x) shl 7)).toByte()
    }

    fun geP3ToBytes(
        s: ByteArray,
        h: GeP3,
    ) {
        require(s.size == 32)
        val recip = Fe()
        val x = Fe()
        val y = Fe()

        FieldOps.feInvert(recip, h.Z)
        FieldOps.feMul(x, h.X, recip)
        FieldOps.feMul(y, h.Y, recip)
        FieldOps.feToBytes(s, y)
        s[31] = (s[31].toInt() xor (FieldOps.feIsNegative(x) shl 7)).toByte()
    }

    fun geFromBytesVartime(
        h: GeP3,
        s: ByteArray,
    ): Boolean {
        require(s.size == 32)
        val u = Fe()
        val v = FeLoose()
        val w = Fe()
        val vxx = Fe()
        val check = FeLoose()

        FieldOps.feFromBytes(h.Y, s)
        FieldOps.fe1(h.Z)
        FieldOps.feSquare(w, h.Y)
        FieldOps.feMul(vxx, w, FieldOps.D)
        FieldOps.feSub(v, w, h.Z)
        FieldOps.feCarry(u, v)
        FieldOps.feAdd(v, vxx, h.Z)

        FieldOps.feMulTtl(w, u, v)
        FieldOps.fePow22523(h.X, w)
        FieldOps.feMul(h.X, h.X, u)

        FieldOps.feSquare(vxx, h.X)
        FieldOps.feMulTtl(vxx, vxx, v)
        FieldOps.feSub(check, vxx, u)
        if (FieldOps.feIsNonZero(check) != 0) {
            FieldOps.feAdd(check, vxx, u)
            if (FieldOps.feIsNonZero(check) != 0) {
                return false
            }
            FieldOps.feMul(h.X, h.X, FieldOps.SQRTM1)
        }

        val signBit = (s[31].toInt() ushr 7) and 1
        if (FieldOps.feIsNegative(h.X) != signBit) {
            val t = FeLoose()
            FieldOps.feNeg(t, h.X)
            FieldOps.feCarry(h.X, t)
        }

        FieldOps.feMul(h.T, h.X, h.Y)
        return true
    }

    // geScalarmult: r = scalar * A (variable-point constant-time scalar multiplication)
    fun geScalarmult(
        r: GeP2,
        scalar: ByteArray,
        A: GeP3,
    ) {
        require(scalar.size == 32)
        val aiP2 = Array(8) { GeP2() }
        val ai = Array(16) { GeCached() }
        val t = GeP1P1()

        geCached_0(ai[0])
        geP3ToCached(ai[1], A)
        geP3ToP2(aiP2[1], A)

        var i = 2
        while (i < 16) {
            geP2Dbl(t, aiP2[i / 2])
            geP1P1ToCached(ai[i], t)
            if (i < 8) {
                geP1P1ToP2(aiP2[i], t)
            }
            geAdd(t, A, ai[i])
            geP1P1ToCached(ai[i + 1], t)
            if (i < 7) {
                geP1P1ToP2(aiP2[i + 1], t)
            }
            i += 2
        }

        geP2_0(r)
        val u = GeP3()

        i = 0
        while (i < 256) {
            geP2Dbl(t, r)
            geP1P1ToP2(r, t)
            geP2Dbl(t, r)
            geP1P1ToP2(r, t)
            geP2Dbl(t, r)
            geP1P1ToP2(r, t)
            geP2Dbl(t, r)
            geP1P1ToP3(u, t)

            val byteIdx = 31 - (i / 8)
            var index = (scalar[byteIdx].toInt() and 0xFF) ushr (4 - (i and 4))
            index = index and 0xF

            val selected = GeCached()
            geCached_0(selected)
            for (j in 0 until 16) {
                val b = if (index == j) 1L else 0L
                cmovCached(selected, ai[j], b)
            }

            geAdd(t, u, selected)
            geP1P1ToP2(r, t)
            i += 4
        }
    }
}
