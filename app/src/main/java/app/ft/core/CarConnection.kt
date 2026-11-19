package app.ft.core

import android.content.Context
import app.ft.FTApp
import app.ft.aa.AaHeadUnitService
import app.ft.carlife.CarLifeService
import app.ft.iccoa.IccoaService

/** App entry points select a backend here. Each backend owns its own connection lifecycle. */
object CarConnection {
    fun startAuto(context: Context) {
        val prefs = FTApp.instance.prefs
        if (!prefs.autoConnect) return
        // Never silently route a stored CarPlay preference to Baidu/ICCOA before iAP2 exists.
        if (!prefs.carProtocol.connectionReady) {
            DiagLog.i("Connection", "CarPlay transport not yet available; no OEM session started")
            return
        }
        val missing = ConnectionPermissions.missing(context)
        if (missing.isNotEmpty()) {
            DiagLog.w("Connection", "automatic connection paused: ${missing.size} mandatory runtime permissions missing")
            return
        }
        when (prefs.carProtocol) {
            CarProtocol.CARLIFE -> {
                IccoaService.stop(context)
                CarLifeService.startAuto(context)
            }
            CarProtocol.ICCOA -> {
                if (CarLifeService.state.value.running) CarLifeService.stop(context)
                IccoaService.start(context)
            }
            CarProtocol.CARPLAY -> {
                // CarPlay is an explicit protocol identity, not a working adapter yet.
                DiagLog.i("Connection", "CarPlay connection unavailable")
            }
        }
    }

    fun switchOn(context: Context) {
        FTApp.instance.prefs.autoConnect = true
        startAuto(context)
    }

    fun switchOff(context: Context) {
        FTApp.instance.prefs.autoConnect = false
        if (CarLifeService.state.value.running) CarLifeService.stop(context)
        IccoaService.stop(context)
        if (AaHeadUnitService.running) AaHeadUnitService.stop(context)
    }

    fun selectProtocol(context: Context, protocol: CarProtocol) {
        val prefs = FTApp.instance.prefs
        // The experimental third choice is selectable WITHOUT becoming a live
        // projection. Do not stop current Baidu/ICCOA/HFP on a diagnostic choice.
        when (CarPlayExperimentalSelectionPolicy.choose(prefs.carProtocol, protocol)) {
            CarPlayExperimentalSelectionPolicy.Action.NO_CHANGE -> {
                // Returning to the already-running real transport only closes
                // the diagnostic selection, without unnecessary reconnect.
                prefs.carPlayExperimentalSelected = (protocol == CarProtocol.CARPLAY)
                return
            }
            CarPlayExperimentalSelectionPolicy.Action.PRESERVE_LIVE_BACKEND_EXPERIMENT_ONLY -> {
                // Deliberately do NOT update prefs.carProtocol: running Baidu
                // and ICCOA check that production key while handling events.
                prefs.carPlayExperimentalSelected = true
                DiagLog.i("Connection",
                    "CarPlay experimental UI selected; production transport unchanged; CarPlay=UNVERIFIED")
                return
            }
            CarPlayExperimentalSelectionPolicy.Action.SWITCH_READY_BACKEND -> Unit
        }
        if (!protocol.connectionReady) return
        prefs.carPlayExperimentalSelected = false
        val wasOn = prefs.autoConnect || CarLifeService.state.value.running || IccoaService.state.value.running
        prefs.carProtocol = protocol
        if (CarLifeService.state.value.running) CarLifeService.stop(context)
        IccoaService.stop(context)
        if (AaHeadUnitService.running) AaHeadUnitService.stop(context)
        DiagLog.i("Connection", "Selected ${protocol.id}; restart=$wasOn")
        if (wasOn) switchOn(context)
    }

    /** Restart the real selected transport, independently of the test UI choice. */
    fun restart(context: Context) {
        if (!FTApp.instance.prefs.carProtocol.connectionReady) {
            DiagLog.i("Connection", "Unavailable production transport; no auto-start")
            return
        }
        if (CarLifeService.state.value.running) CarLifeService.stop(context)
        IccoaService.stop(context)
        if (AaHeadUnitService.running) AaHeadUnitService.stop(context)
        startAuto(context)
    }
}
