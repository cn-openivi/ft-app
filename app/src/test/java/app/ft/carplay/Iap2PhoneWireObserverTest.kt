package app.ft.carplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2PhoneWireObserverTest {
    private val preamble = byteArrayOf(
        0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10
    )

    @Test fun completePhysicalDetectIsNotTreatedAsCarPlayActive() {
        val observer = Iap2PhoneWireObserver()
        val event = observer.accept(preamble)
        assertTrue(event.detectSeen)
        assertEquals(0, event.linkFrameCount)
    }

    @Test fun detectCrossesBluetoothReadBoundaries() {
        val observer = Iap2PhoneWireObserver()
        assertFalse(observer.accept(preamble.copyOfRange(0, 1)).detectSeen)
        assertFalse(observer.accept(preamble.copyOfRange(1, 4)).detectSeen)
        assertTrue(observer.accept(preamble.copyOfRange(4, 6)).detectSeen)
    }

    @Test fun unexpectedRandomPayloadDoesNotCreateFalseIap2Detect() {
        val observer = Iap2PhoneWireObserver()
        assertFalse(observer.accept(byteArrayOf(0xff.toByte(), 0x54, 2, 0, 0xee.toByte(), 0x10)).detectSeen)
        assertFalse(observer.accept(byteArrayOf(0x40, 0x40, 0x43, 0)).detectSeen)
    }

    @Test fun realLinkFramesRemainSeparatelyDetected() {
        val observer = Iap2PhoneWireObserver()
        val linkPacket = Iap2LinkFrameCodec.encode(0x40, 7, 1, 0)
        val event = observer.accept(preamble + linkPacket)
        assertTrue(event.detectSeen)
        assertEquals(1, event.linkFrameCount)
    }

    @Test fun resetClearsPartialPreambleAndLinkBuffer() {
        val observer = Iap2PhoneWireObserver()
        observer.accept(preamble.copyOfRange(0, 3))
        observer.reset()
        assertFalse(observer.accept(preamble.copyOfRange(3, 6)).detectSeen)
        assertTrue(observer.accept(preamble).detectSeen)
    }
}
