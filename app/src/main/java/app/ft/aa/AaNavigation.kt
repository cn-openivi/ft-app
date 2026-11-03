package app.ft.aa

import app.ft.core.NavigationInfo
import app.ft.core.ProtoReader
import kotlin.math.roundToInt

/** Decode documented AA Navigation channel 0x8003..0x8007 message schemas.
 * 0x8006 carries modern steps; 0x8007 carries step_distance, independently.
 * Deprecated 0x8004 and 0x8005 have DIFFERENT payloads, never parse one as the
 * other. The phone controls navigation focus and cluster updates.
 */
class AaNavigation {
    private var info = NavigationInfo()
    private var modern = false
    private var ended = false

    fun reset(): NavigationInfo {
        info = NavigationInfo()
        modern = false
        ended = false
        return info
    }

    fun accept(id: Int, payload: ByteArray): NavigationInfo? {
        val r = ProtoReader(payload)
        when (id) {
            AaProtocol.NAV_STATUS -> when (r.int(1, -1)) {
                0, 2 -> { info = NavigationInfo(); ended = true } // UNAVAILABLE, INACTIVE
                1 -> { ended = false; info = info.copy(active = true) } // ACTIVE
                3 -> { ended = false; info = NavigationInfo(active = true) } // REROUTING
                else -> return null
            }
            AaProtocol.NAV_NOTIFICATION -> {
                if (ended) return null
                val step = r.messages(1).firstOrNull() ?: return null
                val maneuver = step.message(1) ?: return null
                val type = maneuver.int(1, -1)
                if (type !in 0..50) return null
                modern = true
                // field 4 road_info.road_names repeated string is the road name.
                // field 2 instruction.text is fallback if there is no road.
                val road = step.message(4)?.string(1)
                    ?: step.message(2)?.string(1) ?: ""
                setTurn(type, road, maneuver.int(2).coerceAtLeast(0))
            }
            AaProtocol.NAV_NEXT_TURN_DISTANCE -> {
                if (ended) return null
                val step = r.message(1) ?: return null
                val distance = step.message(1) ?: return null
                val meters = meters(distance) ?: return null
                modern = true
                info = info.copy(active = true, distanceMeters = meters,
                    timeSeconds = step.long(2).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            }
            AaProtocol.NAV_LEGACY_TURN_IMAGE -> {
                if (modern || ended) return null
                // 0x8004 NavigationTurnEvent:
                // f1 road, f2 maneuver enum, f3 turn_direction enum,
                // f4 icon bytes, f5 meters, f6 unit.
                val type = r.int(2, -1)
                if (type !in 0..50 || (r.has(3) && r.int(3) !in 0..2)) return null
                setTurn(type, r.string(1) ?: "", 0)
                if (r.has(5) && r.int(5, -1) >= 0)
                    info = info.copy(distanceMeters = r.int(5))
            }
            AaProtocol.NAV_LEGACY_FLAT_TURN -> {
                if (modern || ended) return null
                // 0x8005 deprecated flat maneuver: f1 type, f2 turn_number,
                // f3 turn_distance, f4 unit. Unit semantics of f3 are not
                // confirmed on our HU; never turn it into meters by guessing.
                val type = r.int(1, -1)
                if (type !in 0..50) return null
                setTurn(type, "", 0)
            }
            else -> return null
        }
        return info
    }

    private fun meters(distance: ProtoReader): Int? {
        // 0x8007 step_distance.distance: f1 display value, f2 localized text,
        // f3 DistanceDisplayUnit (0 unknown,1 m,2/3 km,4/5 mi,6 ft,7 yd).
        // A wire capture proves cases with f2 and f3 only. Never assume f1
        // is raw meters when the unit is unknown.
        if (!distance.has(3)) return null
        val unit = distance.int(3)
        val factor = when (unit) {
            1 -> 1.0
            2, 3 -> 1000.0
            4, 5 -> 1609.344
            6 -> 0.3048
            7 -> 0.9144
            else -> return null
        }
        val str = distance.string(2)?.trim()
        val numeric = if (!str.isNullOrEmpty()) {
            // Commas with 3 digits are ambiguous thousands separators.
            if (!str.matches(Regex("[0-9]+(?:[.,][0-9]{1,2})?"))) return null
            str.replace(',', '.').toDoubleOrNull()
        } else distance.int(1, -1).takeIf { distance.has(1) && it >= 0 }?.toDouble()
        val value = numeric ?: return null
        val scaled = value * factor
        if (!scaled.isFinite() || scaled < 0.0 || scaled > Int.MAX_VALUE.toDouble()) return null
        return scaled.roundToInt()
    }

    private fun setTurn(type: Int, road: String, exit: Int) {
        val changed = type != info.maneuver || road != info.road || exit != info.exit
        info = info.copy(active = true, maneuver = type, road = road, exit = exit,
            distanceMeters = if (changed) null else info.distanceMeters,
            timeSeconds = if (changed) 0 else info.timeSeconds)
    }
}
