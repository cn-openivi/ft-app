package app.ft.core

import app.ft.aa.AaProtocol
import java.util.Locale

/**
 * Narrow export-only redaction. Normal transport diagnostics remain intact;
 * Wi-Fi credentials themselves must never be logged.
 */
internal object DiagReportPrivacy {
    // Older FT versions could log BT_WIFI_INFO_RESPONSE raw hex. Redact
    // those stored entries regardless of coordinate-diagnostic settings.
    private val wifiCredentialDump = Regex(
        """(?i)(: TX bt type=${AaProtocol.BT_WIFI_INFO_RESPONSE})(?=\s|$)[^\r\n]*"""
    )
    private val macAddress = Regex(
        """(?i)(?<![0-9a-f])(?:[0-9a-f]{2}[:-]){4}([0-9a-f]{2}[:-][0-9a-f]{2})(?![0-9a-f])"""
    )
    private val songDetails = Regex("""(?i)(told the car the song: )[^\r\n]*""")
    private val coverSize = Regex("""(?i), with cover art \(\d+ bytes\)$""")

    fun scrub(text: String): String {
        val withoutSecrets = wifiCredentialDump.replace(text) { match ->
            "${match.groupValues[1]} payload=REDACTED_WIFI_PASSWORD"
        }
        val withoutSongNames = songDetails.replace(withoutSecrets) { match ->
            "${match.groupValues[1]}[title/artist omitted]" +
                coverSize.find(match.value)?.value.orEmpty()
        }
        return macAddress.replace(withoutSongNames) { match ->
            "xx:xx:xx:xx:" + match.groupValues[1].replace('-', ':').uppercase(Locale.ROOT)
        }
    }
}
