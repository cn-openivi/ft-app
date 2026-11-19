package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class CarPlayExperimentalSelectionPolicyTest {
    private val p = CarPlayExperimentalSelectionPolicy

    @Test fun carPlayCanBeChosenWithoutClaimingConnectionReadiness() {
        assertEquals(CarPlayExperimentalSelectionPolicy.Action.PRESERVE_LIVE_BACKEND_EXPERIMENT_ONLY,
            p.choose(CarProtocol.CARLIFE, CarProtocol.CARPLAY))
        assertEquals(CarPlayExperimentalSelectionPolicy.Action.PRESERVE_LIVE_BACKEND_EXPERIMENT_ONLY,
            p.choose(CarProtocol.ICCOA, CarProtocol.CARPLAY))
        assertFalse(CarProtocol.CARPLAY.connectionReady)
    }

    @Test fun existingBaiduAndIccoaStillSwitchNormally() {
        assertEquals(CarPlayExperimentalSelectionPolicy.Action.SWITCH_READY_BACKEND,
            p.choose(CarProtocol.CARLIFE, CarProtocol.ICCOA))
        assertEquals(CarPlayExperimentalSelectionPolicy.Action.SWITCH_READY_BACKEND,
            p.choose(CarProtocol.ICCOA, CarProtocol.CARLIFE))
    }

    @Test fun restoringReadySelectionFromCarPlayDoesNotGetBlocked() {
        assertEquals(CarPlayExperimentalSelectionPolicy.Action.SWITCH_READY_BACKEND,
            p.choose(CarProtocol.CARPLAY, CarProtocol.CARLIFE))
        assertEquals(CarPlayExperimentalSelectionPolicy.Action.SWITCH_READY_BACKEND,
            p.choose(CarProtocol.CARPLAY, CarProtocol.ICCOA))
    }

    @Test fun selectingSameTransportTwiceDoesNotRestart() {
        for (transport in CarProtocol.entries) {
            assertEquals(CarPlayExperimentalSelectionPolicy.Action.NO_CHANGE, p.choose(transport, transport))
        }
    }
}
