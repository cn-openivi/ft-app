package app.ft.carplay

/**
 * Redacted FT200 physical source-probe handoff. Never contains selected Toyota
 * Bluetooth address, human-readable device name, SSID, password, certificate,
 * raw control packet or operator phone number.
 *
 * A real iAP2 LINK event is NOT proof of MFi, AirPlay or cellular speech.
 */
internal object CarPlayTrialReport {
    fun format(
        trial: CarPlayTrialProgress.State,
        hfp: CarPlayHfpContinuityLedger.State,
        events: List<CarPlayIncomingIapProbe.Event>
    ): String {
        val lines = mutableListOf(
            "FT200 CarPlay early physical diagnostic (redacted)",
            "phase=${trial.phase.name}",
            "blocker=${trial.blocker.name}",
            "real_iap2_link=${trial.realLinkObserved}",
            "real_iap2_control=${trial.realControlObserved}",
            "mfi_authenticated=UNVERIFIED",
            "airplay_session=NOT_STARTED",
            "carplay_active=NO",
            "hfp_profile=${hfp.name}",
            "real_cellular_2way_voice=NOT_TESTED",
            "events(last_14):"
        )
        for (event in events.takeLast(14)) {
            val id = event.messageId?.takeIf { it in 0..65535 }
                ?.let { "0x" + it.toString(16).uppercase() } ?: "none"
            lines.add("${event.status.name};link_frames=${event.validLinkFrames.coerceIn(0,100000)};" +
                "control=$id;parameter_count=${event.parameterCount.coerceIn(0,1024)}")
        }
        return lines.joinToString("\n")
    }
}
