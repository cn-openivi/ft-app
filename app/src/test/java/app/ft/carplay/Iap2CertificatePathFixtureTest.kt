package app.ft.carplay

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Public synthetic unit-test certificates, NOT Apple roots or MFi data.
 * No private key included; fixture valid from 2020 through 2045.
 */
class Iap2CertificatePathFixtureTest {
    private val root = Base64.getDecoder().decode(
        "MIIBYTCCAQagAwIBAgICA+gwCgYIKoZIzj0EAwIwJTEjMCEGA1UEAwwaVW5pdCBUZXN0IFJvb3QgLSBOT1QgQVBQTEUwHhcNMjAwMTAxMDAwMDAwWhcNNDUwMTAxMDAwMDAwWjAlMSMwIQYDVQQDDBpVbml0IFRlc3QgUm9vdCAtIE5PVCBBUFBMRTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABHguplhoDmM8Nvjjf/45M1ZwFN/8ti6egJd1PfaZt+nTjWYD48sVZF9ISy5qdmS5DnQZD36lEgHob/pmw8nF3mKjJjAkMBIGA1UdEwEB/wQIMAYBAf8CAQAwDgYDVR0PAQH/BAQDAgGGMAoGCCqGSM49BAMCA0kAMEYCIQC14YIq83U27pvAoPRXOziX4655YU/mo9pU/5TUyp4ioAIhAJuzAyNs/093fHJLMKOijvcWceFSr2Dk4kfq6Mz0Xb28"
    )
    private val leaf = Base64.getDecoder().decode(
        "MIIBWTCCAQCgAwIBAgICA+kwCgYIKoZIzj0EAwIwJTEjMCEGA1UEAwwaVW5pdCBUZXN0IFJvb3QgLSBOT1QgQVBQTEUwHhcNMjAwMTAxMDAwMDAwWhcNNDUwMTAxMDAwMDAwWjAlMSMwIQYDVQQDDBpVbml0IFRlc3QgTGVhZiAtIE5PVCBBUFBMRTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABCUTuT2QkruwFbjRDTVbM/jyW5YoviqA0i7rxJ0Fl1BKDL3BehKKkTWaRzLEGJIYvsGs88lsQbYwL4QPkkxpR/ijIDAeMAwGA1UdEwEB/wQCMAAwDgYDVR0PAQH/BAQDAgeAMAoGCCqGSM49BAMCA0cAMEQCIC2w5f050aS3uYS81d/WRjKe20kX6G2NHPYWQp2/gHN4AiAwJ8LrWTEgBHBra61GrHk8Jge8QTa4108AZT+Vt9/DnQ=="
    )

    private fun anchor(): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(root)) as X509Certificate

    @Test fun noConfiguredAppleTrustRejectsEvenValidFixtureCertificate() {
        assertEquals(Iap2AccessoryCryptoEvidence.CertificateStatus.NO_APPROVED_ANCHOR,
            Iap2AccessoryCryptoEvidence.verifyCertificatePath(leaf).status)
    }

    @Test fun explicitSyntheticRootProvesOnlyPkixPathAndNotAppleMfi() {
        val result = Iap2AccessoryCryptoEvidence.verifyCertificatePath(
            leaf, setOf(anchor())
        )
        assertEquals(
            Iap2AccessoryCryptoEvidence.CertificateStatus.CHAIN_VALID_REVOCATION_UNCHECKED,
            result.status
        )
        assertEquals(1, result.certificateCount)
    }

    @Test fun malformedCertificateFailsWithoutChangingTrustRoots() {
        val result = Iap2AccessoryCryptoEvidence.verifyCertificatePath(
            byteArrayOf(0x30, 0x00), setOf(anchor())
        )
        assertEquals(
            Iap2AccessoryCryptoEvidence.CertificateStatus.INVALID_CERTIFICATE_ENVELOPE,
            result.status
        )
    }
}
