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
        // Refuse unavailable selections before stopping an existing working session.
        if (!protocol.connectionReady) {
            DiagLog.i("Connection", "Requested ${protocol.id} is not yet available")
            return
        }
        if (prefs.carProtocol == protocol) return
        val wasOn = prefs.autoConnect || CarLifeService.state.value.running || IccoaService.state.value.running
        prefs.carProtocol = protocol
        if (CarLifeService.state.value.running) CarLifeService.stop(context)
        IccoaService.stop(context)
        if (AaHeadUnitService.running) AaHeadUnitService.stop(context)
        DiagLog.i("Connection", "Selected ${protocol.id}; restart=$wasOn")
        if (wasOn) switchOn(context)
    }

    /** Apply a restored protocol/auto-connect preference without keeping the old backend alive. */
    fun restart(context: Context) {
        if (CarLifeService.state.value.running) CarLifeService.stop(context)
        IccoaService.stop(context)
        if (AaHeadUnitService.running) AaHeadUnitService.stop(context)
        startAuto(context)
    }
}
