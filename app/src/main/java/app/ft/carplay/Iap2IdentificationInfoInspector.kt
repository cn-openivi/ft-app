package app.ft.carplay

/**
 * Read-only, redacted inspection of an accessory's iAP2 IdentificationInformation
 * (CONTROL 0x1D01). All payload bytes remain private to the parser; an observed
 * message is NOT authorization, verified identity, or successful MFi authentication.
 *
 * Public reverse-engineering samples describe parameter IDs up to 0x22, but no
 * FAW Toyota profile is established. Unknown IDs are reported, not guessed.
 */
internal object Iap2IdentificationInfoInspector {
    const val IDENTIFICATION_INFORMATION = 0x1D01

    enum class Result {
        NOT_IDENTIFICATION,
        NO_PARAMETERS,
        UNKNOWN_PARAMETER_IDS,
        STRUCTURE_OBSERVED
    }

    data class Summary(
        val result: Result,
        val parameterCount: Int,
        val unknownParameterCount: Int = 0
    )

    fun inspect(message: Iap2ControlSessionCodec.Message): Summary {
        if (message.id != IDENTIFICATION_INFORMATION) {
            return Summary(Result.NOT_IDENTIFICATION, 0)
        }
        if (message.parameters.isEmpty()) {
            return Summary(Result.NO_PARAMETERS, 0)
        }
        // Never inspect or persist user-identifying parameter values: OEM name,
        // serial number, hardware metadata and transport descriptors can be PII.
        val unknown = message.parameters.count { it.id !in 0..0x22 }
        return Summary(
            if (unknown > 0) Result.UNKNOWN_PARAMETER_IDS
            else Result.STRUCTURE_OBSERVED,
            message.parameters.size,
            unknown
        )
    }
}
