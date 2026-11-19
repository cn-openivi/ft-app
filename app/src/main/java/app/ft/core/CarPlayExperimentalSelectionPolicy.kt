package app.ft.core

/**
 * FT200 owner-approved experimental *selection*, not a working CarPlay adapter.
 * A selection is distinct from a live OEM projection connection.
 * This pure policy lets CI prove that the third option does not tear down
 * Baidu/ICCOA, while leaving their existing normal switching behavior intact.
 */
internal object CarPlayExperimentalSelectionPolicy {
    enum class Action {
        NO_CHANGE, PRESERVE_LIVE_BACKEND_EXPERIMENT_ONLY, SWITCH_READY_BACKEND
    }

    /** Visual experiment state never replaces the real connection selection. */
    fun display(production: CarProtocol, experimentSelected: Boolean): CarProtocol =
        if (experimentSelected) CarProtocol.CARPLAY else production

    fun choose(current: CarProtocol, requested: CarProtocol): Action {
        if (current == requested) return Action.NO_CHANGE
        if (requested == CarProtocol.CARPLAY && !requested.connectionReady)
            return Action.PRESERVE_LIVE_BACKEND_EXPERIMENT_ONLY
        return Action.SWITCH_READY_BACKEND
    }
}
