package app.ft.supercast.adb

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCertificatePinTest {
    // Generated local self-signed test-only X509 certificates with the SAME RSA
    // key and DIFFERENT serial numbers/signatures (not real device identities).
    private val first = Base64.getDecoder().decode("MIICozCCAYugAwIBAgIBezANBgkqhkiG9w0BAQsFADAVMRMwEQYDVQQDDApBREIgRGV2aWNlMB4XDTI0MDEwMTAwMDAwMFoXDTMyMDEwMTAwMDAwMFowFTETMBEGA1UEAwwKQURCIERldmljZTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMAaX9ZycMpPdlFyEhUoFikdvitiRn2NdPsaEWp/An5yXpNFUlSwapUQl40Y+CQ+6dfsejItwVxlN5hLLqx1DpsZwKJk/79ROLwf4P4Og4dywu+hl2dhtX7j9IwuekhwIki0VPoG/jxza/+7od388cCmXwCoMdD08pdOkscyKdRpBMixzlY9ZBwOH7PvydcZGbePzFMrdamVWx3latBBFaXv8cdd/oBExp33mvDbVb0Mo70Qo5RDh8oug1I1kl1I3SAmQTex9CIwO4/zuZzSIy3prn95pRsNRO3zesuSL04mIzOrBC2nHr4ig5S1TtUGwc7jPhI7y8bo7zI32ci3x28CAwEAATANBgkqhkiG9w0BAQsFAAOCAQEAE8F8odlj/O8Zl2I8uT/5IEsFgKfGlDscRcoD5OQG4IdLjNtky+iNuxM5KgGJuUJI2Jh6L68CJHDSFfjgVnU3vlavFK5MN34OYR8Aw6iVhkWitMn7J+Bt6jG03fO65MjnJC/lY/K6EEbInd0GRIB41xvxvmW+pDDpfAF0Ad/0iih6zlRr2KxsAhhg1WyGLMSHf0a1kFi3joqGJp0WRj2c4qZv+azQlnoIMXneZYgQXaP6pYnvSWnhCru2AJUQ4gs80cRrcAU4D4cm0jENC5ZrRe4OSv9/IOk8ltsAcRVqi8cWqem+DQO2bDiPV94BQv9fvAMwH8/ALtv9NQf23jtQ7A==")
    private val rotatedCertSameKey = Base64.getDecoder().decode("MIICozCCAYugAwIBAgIBfDANBgkqhkiG9w0BAQsFADAVMRMwEQYDVQQDDApBREIgRGV2aWNlMB4XDTI0MDEwMTAwMDAwMFoXDTMyMDEwMTAwMDAwMFowFTETMBEGA1UEAwwKQURCIERldmljZTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMAaX9ZycMpPdlFyEhUoFikdvitiRn2NdPsaEWp/An5yXpNFUlSwapUQl40Y+CQ+6dfsejItwVxlN5hLLqx1DpsZwKJk/79ROLwf4P4Og4dywu+hl2dhtX7j9IwuekhwIki0VPoG/jxza/+7od388cCmXwCoMdD08pdOkscyKdRpBMixzlY9ZBwOH7PvydcZGbePzFMrdamVWx3latBBFaXv8cdd/oBExp33mvDbVb0Mo70Qo5RDh8oug1I1kl1I3SAmQTex9CIwO4/zuZzSIy3prn95pRsNRO3zesuSL04mIzOrBC2nHr4ig5S1TtUGwc7jPhI7y8bo7zI32ci3x28CAwEAATANBgkqhkiG9w0BAQsFAAOCAQEAMtgbqT2s5R3AI0y5DJQCLMiBa2UAVTouZ/D5OkAhfs7k65uqJyLILRPBUcY5VzoyW22fk5YS+DmxJbpK7E1tZBVAkW9JPHQPhTz7mjZwNnguDFYtA5+HhAceMHuTzJpkLBQSgfaPS/5n60Dq4/00Jgi5Hx4Lr8gly1R3KaymH5c3vJ0rTBxQOsINAWy0xYHbksQTzYO1cMEi6m7oQwLRIrBtNIZw0jT342gwYDgq+MOmVpwWPL3kVpWPeHFla8kylbX8cXsY7GSujp0khc+5Pu0XhkTJg8202a8uCnmlenIS8DuoANnE2NViOx6Q5Pr4LOkouqzNXSTq1o83CzNgZg==")

    @Test fun certificateRotationWithSameConnectKeyKeepsTrust() {
        assertFalse(MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(first),
            MessageDigest.getInstance("SHA-256").digest(rotatedCertSameKey)
        ))
        val trusted = PeerCertificatePin.connectPublicKeySha256(first)
        assertArrayEquals(trusted, PeerCertificatePin.connectPublicKeySha256(rotatedCertSameKey))
        assertTrue(PeerCertificatePin.matchesConnectPublicKey(rotatedCertSameKey, trusted))
    }

    @Test fun unknownOrCorruptPeerFailsClosed() {
        val trusted = PeerCertificatePin.connectPublicKeySha256(first)
        assertFalse(PeerCertificatePin.matchesConnectPublicKey(first, ByteArray(32)))
        assertFalse(PeerCertificatePin.matchesConnectPublicKey(first, ByteArray(31)))
        var rejected = false
        try { PeerCertificatePin.connectPublicKeySha256(byteArrayOf(0x01, 0x02)) }
        catch (_: Exception) { rejected = true }
        assertTrue(rejected)
    }
}
