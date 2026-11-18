package app.ft.iccoa

import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Test

class IccoaBindFailureReasonTest {
    @Test fun recognizesPolicyDenialWithoutPrintingAddress() {
        val reason = SocketException("Binding socket to network 375 failed: EPERM (Operation not permitted) peer=10.0.0.1")
        assertEquals("EPERM", IccoaBindFailureReason.classify(reason))
        assertEquals("EACCES", IccoaBindFailureReason.classify(
            SocketException("EACCES: network access denied")))
    }

    @Test fun recognizesBadSocketAndLostRoute() {
        assertEquals("EBADF", IccoaBindFailureReason.classify(SocketException("Bad file descriptor: EBADF")))
        assertEquals("ENETUNREACH", IccoaBindFailureReason.classify(
            SocketException("Network is unreachable (ENETUNREACH)")))
        assertEquals("ALREADY_CONNECTED", IccoaBindFailureReason.classify(
            SocketException("Socket is connected")))
    }

    @Test fun traversesCauseButNeverReturnsOriginalMessage() {
        val wrapped = RuntimeException("wrapper with sensitive phone identity",
            SocketException("invalid argument (EINVAL) / 192.168.1.1"))
        assertEquals("EINVAL", IccoaBindFailureReason.classify(wrapped))
        assertEquals("UNKNOWN", IccoaBindFailureReason.classify(SocketException("some private details")))
        assertEquals("UNKNOWN", IccoaBindFailureReason.classify(
            SocketException("prefixXEPERM123 not a standalone errno")))
    }
}
