package io.github.rhythmache.spake2

import io.github.rhythmache.spake2.internal.Spake2Constants
import io.github.rhythmache.spake2.internal.field.GeCached
import io.github.rhythmache.spake2.internal.field.GeOps
import io.github.rhythmache.spake2.internal.field.GeP1P1
import io.github.rhythmache.spake2.internal.field.GeP2
import io.github.rhythmache.spake2.internal.field.GeP3
import io.github.rhythmache.spake2.internal.field.ScalarOps
import java.security.MessageDigest
import java.security.SecureRandom

private enum class Spake2State { INIT, MSG_GENERATED, KEY_GENERATED }

class Spake2Ctx(
    private val role: Spake2Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
) {
    private var state = Spake2State.INIT

    private var privateKeyScalarBytes: ByteArray? = null
    private var passwordScalarBytes: ByteArray? = null
    private var passwordHash: ByteArray? = null
    private var myMsg: ByteArray? = null

    private val secureRandom = SecureRandom()

    /**
     * Equivalent of SPAKE2_generate_msg.
     * Must be called exactly once. Returns the 32-byte message to send to the peer.
     */
    fun generateMessage(password: ByteArray): ByteArray {
        check(state == Spake2State.INIT) { "generateMessage() already called" }
        require(password.isNotEmpty()) { "password must not be empty" }

        // Generate ephemeral private key: 64 random bytes -> sc_reduce -> shiftLeft(3)
        val randomBytes = ByteArray(64)
        secureRandom.nextBytes(randomBytes)
        val reducedPrivate = ByteArray(32)
        ScalarOps.scReduce(reducedPrivate, randomBytes)
        val privateKeyBytes = ByteArray(32)
        ScalarOps.scalarMul8(privateKeyBytes, reducedPrivate)
        privateKeyScalarBytes = privateKeyBytes

        // P = privateKey * BasePoint
        val basePointP3 = GeP3()
        check(GeOps.geFromBytesVartime(basePointP3, Spake2Constants.B_ENCODED))
        val pP2 = GeP2()
        GeOps.geScalarmult(pP2, privateKeyBytes, basePointP3)

        // password_hash = SHA512(password), kept in full for later transcript use
        val sha512 = MessageDigest.getInstance("SHA-512")
        val pwHash = sha512.digest(password)
        passwordHash = pwHash

        // password_scalar = cofactor_hack(reduce(password_hash))
        val reducedPwScalar = ByteArray(32)
        ScalarOps.scReduce(reducedPwScalar, pwHash)
        val fixedPasswordScalar = ByteArray(32)
        ScalarOps.applyPasswordScalarCofactorHack(fixedPasswordScalar, reducedPwScalar)
        passwordScalarBytes = fixedPasswordScalar

        // mask = password_scalar * (M if Alice else N)
        val maskPointEncoded =
            if (role == Spake2Role.ALICE) {
                Spake2Constants.M_ENCODED
            } else {
                Spake2Constants.N_ENCODED
            }
        val maskBasePointP3 = GeP3()
        check(GeOps.geFromBytesVartime(maskBasePointP3, maskPointEncoded))
        val maskP2 = GeP2()
        GeOps.geScalarmult(maskP2, fixedPasswordScalar, maskBasePointP3)

        // P* = P + mask
        val pP3 = GeP3()
        val pBytes = ByteArray(32)
        GeOps.geToBytes(pBytes, pP2)
        check(GeOps.geFromBytesVartime(pP3, pBytes))

        val maskP3 = GeP3()
        val maskBytes = ByteArray(32)
        GeOps.geToBytes(maskBytes, maskP2)
        check(GeOps.geFromBytesVartime(maskP3, maskBytes))
        val maskCached = GeCached()
        GeOps.geP3ToCached(maskCached, maskP3)

        val pStarP1P1 = GeP1P1()
        GeOps.geAdd(pStarP1P1, pP3, maskCached)

        val pStarP2 = GeP2()
        GeOps.geP1P1ToP2(pStarP2, pStarP1P1)

        //  Encode P*
        val encoded = ByteArray(32)
        GeOps.geToBytes(encoded, pStarP2)
        myMsg = encoded
        state = Spake2State.MSG_GENERATED

        return encoded
    }

    /**
     * Equivalent of SPAKE2_process_msg.
     * Must be called exactly once, after generateMessage(). Returns the 64-byte
     * SHA-512 derived key material.
     */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        check(state == Spake2State.MSG_GENERATED) {
            "Must call generateMessage() before processMessage(), and only once"
        }
        require(theirMsg.size == 32) { "their_msg must be exactly 32 bytes" }

        val myMsgBytes = myMsg ?: error("myMsg not set")
        val myPrivate = privateKeyScalarBytes ?: error("privateKeyScalarBytes not set")
        val myPasswordScalar = passwordScalarBytes ?: error("passwordScalarBytes not set")
        val myPwHash = passwordHash ?: error("passwordHash not set")

        // Decode peer's P*
        val theirPStarP3 = GeP3()
        require(GeOps.geFromBytesVartime(theirPStarP3, theirMsg)) { "Invalid peer point" }

        // Unmask peer's point using OPPOSITE mask point
        val peersMaskPointEncoded =
            if (role == Spake2Role.ALICE) {
                Spake2Constants.N_ENCODED
            } else {
                Spake2Constants.M_ENCODED
            }
        val peersMaskBasePointP3 = GeP3()
        check(GeOps.geFromBytesVartime(peersMaskBasePointP3, peersMaskPointEncoded))
        val peersMaskP2 = GeP2()
        GeOps.geScalarmult(peersMaskP2, myPasswordScalar, peersMaskBasePointP3)

        val peersMaskP3 = GeP3()
        val peersMaskBytes = ByteArray(32)
        GeOps.geToBytes(peersMaskBytes, peersMaskP2)
        check(GeOps.geFromBytesVartime(peersMaskP3, peersMaskBytes))

        val peersMaskCached = GeCached()
        GeOps.geP3ToCached(peersMaskCached, peersMaskP3)

        // Q = theirPStar - peersMask
        val qP1P1 = GeP1P1()
        GeOps.geSub(qP1P1, theirPStarP3, peersMaskCached)
        val qP3 = GeP3()
        GeOps.geP1P1ToP3(qP3, qP1P1)

        // Shared point = myPrivate * Q
        val dhSharedP2 = GeP2()
        GeOps.geScalarmult(dhSharedP2, myPrivate, qP3)
        val dhSharedEncoded = ByteArray(32)
        GeOps.geToBytes(dhSharedEncoded, dhSharedP2)

        // Build transcript hash (SHA-512)
        val sha512 = MessageDigest.getInstance("SHA-512")

        if (role == Spake2Role.ALICE) {
            updateWithLengthPrefix(sha512, myName)
            updateWithLengthPrefix(sha512, theirName)
            updateWithLengthPrefix(sha512, myMsgBytes)
            updateWithLengthPrefix(sha512, theirMsg)
        } else {
            updateWithLengthPrefix(sha512, theirName)
            updateWithLengthPrefix(sha512, myName)
            updateWithLengthPrefix(sha512, theirMsg)
            updateWithLengthPrefix(sha512, myMsgBytes)
        }
        updateWithLengthPrefix(sha512, dhSharedEncoded)
        updateWithLengthPrefix(sha512, myPwHash)

        val key = sha512.digest()
        state = Spake2State.KEY_GENERATED

        return key
    }

    private fun updateWithLengthPrefix(
        digest: MessageDigest,
        data: ByteArray,
    ) {
        val lenLe = ByteArray(8)
        var l = data.size.toLong()
        for (i in 0 until 8) {
            lenLe[i] = (l and 0xff).toByte()
            l = l ushr 8
        }
        digest.update(lenLe)
        digest.update(data)
    }
}
