package app.ft.supercast.adb

/**
 * One-shot, user-triggered Accessibility code extraction.
 *
 * Never monitors arbitrary apps: the caller must supply the exact Settings package
 * resolved from ACTION_APPLICATION_DEVELOPMENT_SETTINGS before arming. The pairing
 * dialog is only eligible while Android is advertising a fresh _adb-tls-pairing
 * endpoint. No code is persisted or logged. An armed capture expires.
 *
 * Source-only FT182 building block: does not pair or start an ADB session.
 */
internal class WirelessPairingWindowCapture(
    private val monotonicMs: () -> Long
) {
    private var settingsPackage: String? = null
    private var expiresAt: Long = 0

    @Synchronized fun arm(resolvedSettingsPackage: String, durationMs: Long = 90_000L) {
        require(resolvedSettingsPackage.isNotBlank())
        settingsPackage = resolvedSettingsPackage
        expiresAt = monotonicMs() + durationMs.coerceIn(1_000L, 120_000L)
    }

    @Synchronized fun cancel() {
        settingsPackage = null
        expiresAt = 0
    }

    @Synchronized fun codeForOpenedSystemDialog(
        activeWindowPackage: String?,
        visibleNodeTexts: List<String>,
        mdnsPairingPort: Int?
    ): String? {
        val expected = settingsPackage ?: return null
        if (activeWindowPackage != expected || monotonicMs() >= expiresAt ||
            mdnsPairingPort == null || mdnsPairingPort !in 1..65535) return null

        // This pattern applies only to an explicitly armed Settings window with a
        // current pairing service. The six digits are an ephemeral secret.
        val code = visibleNodeTexts.firstNotNullOfOrNull { raw ->
            raw.trim().replace(" ", "").takeIf { value ->
                value.length == 6 && value.all(Char::isDigit)
            }
        } ?: return null
        cancel() // Consume once, never keep a live screen monitor.
        return code
    }
}
