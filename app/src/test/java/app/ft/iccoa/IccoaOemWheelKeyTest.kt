package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test

class IccoaOemWheelKeyTest {
    @Test fun actualOemKeyCodesAndWireMethodFromCarlink() {
        assertEquals(1, IccoaOemWheelKey.CATEGORY)
        assertEquals(12, IccoaOemWheelKey.CONTROL_METHOD)
        assertEquals(87, IccoaOemWheelKey.NEXT)
        assertEquals(88, IccoaOemWheelKey.PREVIOUS)
    }

    @Test fun toyotaSynthesisedDownAndUpDispatchOnceForNext() {
        val down = IccoaOemWheelKey.decode(byteArrayOf(0x10, 0x57))
        val up = IccoaOemWheelKey.decode(byteArrayOf(0x08, 0x01, 0x10, 0x57))
        assertNotNull(down)
        assertEquals(87, down!!.code)
        assertEquals(0, down.action)
        assertTrue(down.dispatchTrack)
        assertNotNull(up)
        assertEquals(87, up!!.code)
        assertEquals(1, up.action)
        assertFalse(up.dispatchTrack)
    }

    @Test fun previousAndExplicitPressTargetThePhone() {
        val previous = IccoaOemWheelKey.decode(byteArrayOf(0x08, 0x02, 0x10, 0x58))
        assertNotNull(previous)
        assertEquals(88, previous!!.code)
        assertEquals(2, previous.action)
        assertTrue(previous.dispatchTrack)
    }

    @Test fun unrelatedVoiceSearchAndMalformedFramesCannotBecomeTrackCommands() {
        assertNull(IccoaOemWheelKey.decode(byteArrayOf(0x10, 0x54)))
        assertNull(IccoaOemWheelKey.decode(byteArrayOf()))
        assertNull(IccoaOemWheelKey.decode(byteArrayOf(0x10)))
        assertNull(IccoaOemWheelKey.decode(byteArrayOf(0x08, 0x03, 0x10, 0x57)))
        assertNull(IccoaOemWheelKey.decode(byteArrayOf(0x10, 0x57, 0x10, 0x58)))
        assertNull(IccoaOemWheelKey.decode(ByteArray(129)))
    }

    @Test fun unknownOptionalMetadataMayNotChangeMediaCommand() {
        val event = IccoaOemWheelKey.decode(byteArrayOf(0x10, 0x58, 0x18, 0x00, 0x20, 0x7b))
        assertNotNull(event)
        assertEquals(88, event!!.code)
        assertEquals(0, event.metaState)
        assertEquals(123L, event.sourceTimestampMs)
    }
}
