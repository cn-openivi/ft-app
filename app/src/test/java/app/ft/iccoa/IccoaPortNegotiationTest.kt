package app.ft.iccoa

import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import org.junit.Assert.*
import org.junit.Test

class IccoaPortNegotiationTest {
    @Test fun knownSensorRequestReturnsBoundPort() {
        val request = ProtoWriter().enum(1, 1).toByteArray()
        val response = IccoaPortNegotiation.responseFor(request)!!
        val parsed = ProtoReader(response)
        assertEquals(1, parsed.int(1))
        assertEquals(57239, parsed.int(2))
    }
    @Test fun incompleteOrAmbiguousRequestsNeverReceivePorts() {
        val good = ProtoWriter().enum(1, 1).toByteArray()
        assertNull(IccoaPortNegotiation.responseFor(good + byteArrayOf(0x10)))
        assertNull(IccoaPortNegotiation.responseFor(good + good))
        assertNull(IccoaPortNegotiation.responseFor(good + byteArrayOf(0x0A, 0x00)))
        assertNull(IccoaPortNegotiation.responseFor(byteArrayOf(0x08)))
        assertNull(IccoaPortNegotiation.responseFor(byteArrayOf(0x08, 0x81.toByte())))
    }
    @Test fun forwardCompatibleRequestWithCompleteUnknownFieldsIsAccepted() {
        val request = ProtoWriter().enum(1, 1).int64(2, 123).int64(3, 123)
            .bytes(4, byteArrayOf(0x10, 0x20)).fixed32(5, 0x12345678)
            .fixed64(6, 42).toByteArray()
        assertEquals(57239, ProtoReader(IccoaPortNegotiation.responseFor(request)!!).int(2))
    }
    @Test fun malformedExtensionsAndDuplicateSensorAreRejected() {
        val good = ProtoWriter().enum(1, 1).toByteArray()
        assertNull(IccoaPortNegotiation.responseFor(good + byteArrayOf(0x1A, 0x05, 0x11)))
        assertNull(IccoaPortNegotiation.responseFor(good + byteArrayOf(0x19, 0x11)))
        assertNull(IccoaPortNegotiation.responseFor(good + byteArrayOf(0x1F)))
        assertNull(IccoaPortNegotiation.responseFor(good + ProtoWriter().enum(1, 1).toByteArray()))
    }
    @Test fun unknownTypesAreNotAssignedUnverifiedPorts() {
        assertNull(IccoaPortNegotiation.responseFor(ProtoWriter().enum(1, 2).toByteArray()))
        assertNull(IccoaPortNegotiation.responseFor(ProtoWriter().enum(1, 3).toByteArray()))
        assertNull(IccoaPortNegotiation.responseFor(ByteArray(0)))
        assertNull(IccoaPortNegotiation.responseFor(ProtoWriter().enum(1, 0).bool(2, true).toByteArray()))
    }
}
