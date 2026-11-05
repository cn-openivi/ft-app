package app.ft.iccoa

/**
 * Guards Android Network callbacks and asynchronous auth commits.
 * A superseded Network cannot bind sockets, commit keys, or tear down
 * its replacement. All public operations are synchronized.
 */
internal class IccoaNetworkCallbackGate<T : Any> {
    private var selected: T? = null
    private var lastLost: T? = null

    @Synchronized fun onAvailable(network: T): Boolean {
        val changed = selected != network
        selected = network
        lastLost = null
        return changed
    }

    @Synchronized fun isCurrent(network: T): Boolean = selected == network

    @Synchronized fun bindIfCurrent(network: T, bind: () -> Unit): Boolean {
        if (selected != network) return false
        bind()
        return true
    }

    @Synchronized fun commitIfCurrent(network: T, commit: () -> Unit): Boolean {
        if (selected != network) return false
        commit()
        return true
    }

    @Synchronized fun onLost(network: T): Boolean {
        if (selected != network) return false
        selected = null
        lastLost = network
        return true
    }

    @Synchronized fun isUnreplacedLoss(network: T): Boolean =
        selected == null && lastLost == network

    @Synchronized fun clear() {
        selected = null
        lastLost = null
    }
}
