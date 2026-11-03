package app.ft.iccoa

import app.ft.core.NavigationInfo
import app.ft.core.ProtoWriter
import app.ft.carlife.NavigationRelay

/**
 * FAW RAV4 OEM Carlink.apk ucar.proto ControlIndex.notify_navigation_info = 11.
 *
 * CarLinkAdapter's onNavigationInfoReceived toggles CarLinkSensorMgr on/off
 * based on isNavigating. That SDK path produces GPS through UCarAdapter.
 * Explicitly model the difference between a guided route and an AA projection
 * sensor activation request; this control message is an OEM P1 hypothesis,
 * not proof that the HU will actually provide GPS.
 */
internal object IccoaNavigation {
    const val CONTROL_METHOD = 11

    /** Enable the OEM GPS producer only during a live AA projection or guided route.
     * This is not asserted to be a user-started turn-by-turn navigation session.
     */
    fun sensorActivation(
        info: NavigationInfo,
        aaConnected: Boolean,
        ftProjectionActive: Boolean = false
    ): NavigationInfo =
        if ((aaConnected || ftProjectionActive) && !info.active) info.copy(active = true) else info

    /** Exact OEM ucar.proto NotifyNavigationInfo, not an Android Auto protobuf.
     * Fields: 1 navigating, 2 PNG turn icon, 3 distance, 4 unit, 5 action,
     * 6 where, 7/8 titles, 9 optional app id (not fabricated here).
     * A projection-only GPS activation never invents a turn.
     */
    fun encode(info: NavigationInfo, directionIconPng: ByteArray? = null): ByteArray {
        val icon = directionIconPng?.takeIf { it.size in 1..65_536 }
        val guidedTurn = info.active && info.maneuver > 0 &&
            info.distanceMeters != null && icon != null
        val road = info.road.take(128)
        val w = ProtoWriter().bool(1, info.active)
        if (guidedTurn) {
            w.bytes(2, requireNotNull(icon))
            val operation = operation(info.maneuver)
            w.string(5, operation)
                .string(7, operation)
                .string(8, road)
        }
        return w.string(3, info.distanceMeters?.coerceAtLeast(0)?.toString() ?: "")
            .string(4, "m")
            .string(6, road)
            .toByteArray()
    }

    private fun operation(maneuver: Int): String = when (NavigationRelay.turnCode(maneuver)) {
        2, 3, 4 -> "Turn right"
        6, 7, 8 -> "Turn left"
        5 -> "Make a U-turn"
        9, 10 -> "At roundabout"
        24 -> "Arrive"
        else -> "Continue"
    }
}
