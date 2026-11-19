package app.ft.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayAvcPassthroughTest {
    // Minimal independently generated 1920x1080, baseline L4.0, not a
    // copied Apple video sample or a claim about live FAW Toyota capability.
    private val sps = byteArrayOf(0x67,0x42,0,0x28,0xF4.toByte(),3,0xC0.toByte(),
        0x11,0x3F,0x2A)
    private val csd = byteArrayOf(0,0,0,1) + sps +
        byteArrayOf(0,0,0,1,0x68,0)
    private val peer = CarPlayMediaPreflight.Receiver(
        1920,1080,30,setOf(66),40,true,true
    )
    private val idr = byteArrayOf(0,0,0,1,0x65,0x11)
    private val pframe = byteArrayOf(0,0,0,1,0x41,0x44)

    @Test fun cannotStartCompressedVideoWithoutPeerAndAvcConfiguration() {
        val gate = CarPlayAvcPassthrough()
        assertEquals(CarPlayAvcPassthrough.Result.AIRPLAY_PEER_UNVERIFIED,
            gate.configure(csd,30,null))
        assertEquals(CarPlayAvcPassthrough.Result.SOURCE_SPS_INVALID,
            gate.configure(byteArrayOf(0,0,0,1,0x67),30,peer))
        assertEquals(CarPlayAvcPassthrough.Result.NO_CONFIG,
            gate.offer(idr,123) { _,_ -> true })
    }

    @Test fun firstFrameMustBeIdrAndNoVideoReencodingOrCopy() {
        val gate = CarPlayAvcPassthrough()
        assertEquals(CarPlayAvcPassthrough.Result.CONFIG_READY_WAITING_IDR,
            gate.configure(csd,30,peer))
        var called = false
        assertEquals(CarPlayAvcPassthrough.Result.NEED_IDR,
            gate.offer(pframe,1000) { _,_ -> called=true; true })
        assertFalse(called)
        assertEquals(CarPlayAvcPassthrough.Result.ORIGINAL_COMPRESSED_AU_CONSUMED,
            gate.offer(idr,1033) { data,pts ->
                assertTrue(data === idr)
                assertEquals(1033,pts)
                true
            })
        assertEquals(CarPlayAvcPassthrough.Result.ORIGINAL_COMPRESSED_AU_CONSUMED,
            gate.offer(pframe,1066) { data,_ ->
                assertTrue(data === pframe)
                true
            })
    }

    @Test fun backpressureDropsInsteadOfQueueOrPhantomDelivery() {
        val gate = CarPlayAvcPassthrough()
        gate.configure(csd,30,peer)
        assertEquals(CarPlayAvcPassthrough.Result.BACKPRESSURE_DROP,
            gate.offer(idr,1234) { _,_ -> false })
        assertEquals(CarPlayAvcPassthrough.Result.NEED_IDR,
            gate.offer(pframe,1235) { _,_ -> true })
        assertEquals(CarPlayAvcPassthrough.Result.ORIGINAL_COMPRESSED_AU_CONSUMED,
            gate.offer(idr,1236) { _,_ -> true })
    }

    @Test fun encryptionPacketizationAndProfileMustMatchKnownReceiver() {
        val gate = CarPlayAvcPassthrough()
        gate.configure(csd,30,peer.copy(encryptionProven = false))
        assertEquals(CarPlayAvcPassthrough.Result.PROFILE_OR_SECURITY_MISMATCH,
            gate.offer(idr,100) { _,_ -> true })
        gate.configure(csd,30,peer.copy(packetizationProven = false))
        assertEquals(CarPlayAvcPassthrough.Result.PROFILE_OR_SECURITY_MISMATCH,
            gate.offer(idr,100) { _,_ -> true })
        gate.configure(csd,30,peer.copy(profiles = setOf(100)))
        assertEquals(CarPlayAvcPassthrough.Result.PROFILE_OR_SECURITY_MISMATCH,
            gate.offer(idr,100) { _,_ -> true })
    }

    @Test fun throwingSinkNeverClaimsDeliveryAndCannotCorruptIdrGate() {
        val gate = CarPlayAvcPassthrough()
        gate.configure(csd,30,peer)
        assertEquals(CarPlayAvcPassthrough.Result.BACKPRESSURE_DROP,
            gate.offer(idr,42) { _,_ -> error("downstream failed") })
        assertEquals(CarPlayAvcPassthrough.Result.NEED_IDR,
            gate.offer(pframe,43) { _,_ -> true })
    }

    @Test fun droppedPredictedFrameInvalidatesDecoderReferenceChain() {
        val gate = CarPlayAvcPassthrough()
        gate.configure(csd,30,peer)
        assertEquals(CarPlayAvcPassthrough.Result.ORIGINAL_COMPRESSED_AU_CONSUMED,
            gate.offer(idr,1000) { _,_ -> true })
        assertEquals(CarPlayAvcPassthrough.Result.BACKPRESSURE_DROP,
            gate.offer(pframe,2000) { _,_ -> false })
        var delivered = false
        assertEquals(CarPlayAvcPassthrough.Result.NEED_IDR,
            gate.offer(pframe,3000) { _,_ -> delivered = true; true })
        assertFalse(delivered)
        assertEquals(CarPlayAvcPassthrough.Result.ORIGINAL_COMPRESSED_AU_CONSUMED,
            gate.offer(idr,4000) { _,_ -> true })
        assertEquals(CarPlayAvcPassthrough.Result.ORIGINAL_COMPRESSED_AU_CONSUMED,
            gate.offer(pframe,5000) { _,_ -> true })
    }

    @Test fun discontinuousPtsRequiresNewIdrInsteadOfResumingPredictedVideo() {
        val gate = CarPlayAvcPassthrough(maxDecodeGapUs = 100_000L)
        gate.configure(csd,30,peer)
        gate.offer(idr,100L) { _,_ -> true }
        assertEquals(CarPlayAvcPassthrough.Result.NEED_IDR_AFTER_GAP,
            gate.offer(pframe,200_101L) { _,_ -> true })
        assertEquals(CarPlayAvcPassthrough.Result.NEED_IDR_AFTER_GAP,
            gate.offer(pframe,200_102L) { _,_ -> true })
        assertEquals(CarPlayAvcPassthrough.Result.ORIGINAL_COMPRESSED_AU_CONSUMED,
            gate.offer(idr,200_103L) { _,_ -> true })
        assertEquals(CarPlayAvcPassthrough.Result.ORIGINAL_COMPRESSED_AU_CONSUMED,
            gate.offer(pframe,200_104L) { _,_ -> true })
    }


    @Test fun sourceCountersSeparateRejectedFramesFromAcceptedSinkConsumption() {
        val gate = CarPlayAvcPassthrough(maxDecodeGapUs = 100_000L)
        gate.configure(csd,30,peer)
        assertEquals(CarPlayAvcPassthrough.Result.NEED_IDR,
            gate.offer(pframe,1L) { _,_ -> true })
        assertEquals(CarPlayAvcPassthrough.Result.ORIGINAL_COMPRESSED_AU_CONSUMED,
            gate.offer(idr,100L) { _,_ -> true })
        assertEquals(CarPlayAvcPassthrough.Result.BACKPRESSURE_DROP,
            gate.offer(pframe,110L) { _,_ -> false })
        assertEquals(CarPlayAvcPassthrough.Result.NEED_IDR_AFTER_GAP,
            gate.offer(pframe,200_101L) { _,_ -> true })
        assertEquals(CarPlayAvcPassthrough.Result.TIMESTAMP_INVALID,
            gate.offer(idr,90L) { _,_ -> true })
        assertEquals(CarPlayAvcPassthrough.Result.ORIGINAL_COMPRESSED_AU_CONSUMED,
            gate.offer(idr,200_102L) { _,_ -> true })
        val c = gate.counters()
        assertEquals(6L, c.offeredAUs)
        assertEquals(2L, c.acceptedBySinkAUs)
        assertEquals(1L, c.sinkBackpressureDrops)
        assertEquals(2L, c.withheldForIdr)
        // Backpressure already invalidated IDR: no separate continuity
        // episode is counted for subsequent gap while waiting for IDR.
        assertEquals(0L, c.ptsDiscontinuities)
        assertEquals(1L, c.rejectedPts)
        gate.reset()
        assertEquals(0L, gate.counters().offeredAUs)
    }

    @Test fun sourceCountersTrackOneLongGapAcrossMultipleRejectedPFrames() {
        val gate = CarPlayAvcPassthrough(maxDecodeGapUs = 100_000L)
        gate.configure(csd,30,peer)
        gate.offer(idr,1L) { _,_ -> true }
        gate.offer(pframe,200_002L) { _,_ -> true }
        gate.offer(pframe,200_003L) { _,_ -> true }
        assertEquals(1L, gate.counters().ptsDiscontinuities)
        assertEquals(2L, gate.counters().withheldForIdr)
        gate.offer(idr,200_004L) { _,_ -> true }
        assertEquals(2L, gate.counters().acceptedBySinkAUs)
    }
    @Test fun duplicatedTimestampsAndDisconnectResetAreSafe() {
        val gate = CarPlayAvcPassthrough()
        gate.configure(csd,30,peer)
        gate.offer(idr,100) { _,_ -> true }
        assertEquals(CarPlayAvcPassthrough.Result.TIMESTAMP_INVALID,
            gate.offer(pframe,100) { _,_ -> true })
        gate.reset()
        assertEquals(CarPlayAvcPassthrough.Result.NO_CONFIG,
            gate.offer(idr,101) { _,_ -> true })
    }
}
