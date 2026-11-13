package app.ft.supercast.adb

import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCertificatePinTest {
    @Test fun refusesUnknownOrChangedTlsPeer() {
        val firstDer = byteArrayOf(0x30, 0x03, 0x01, 0x02, 0x03)
        val good = MessageDigest.getInstance("SHA-256").digest(firstDer)
        assertTrue(PeerCertificatePin.matches(firstDer, good))
        assertFalse(PeerCertificatePin.matches(firstDer, ByteArray(32)))
        assertFalse(PeerCertificatePin.matches(firstDer, ByteArray(31)))
        assertFalse(PeerCertificatePin.matches(byteArrayOf(0x30, 0x03, 0x01, 0x02, 0x04), good))
        assertFalse(PeerCertificatePin.matches(byteArrayOf(), good))
    }
}
