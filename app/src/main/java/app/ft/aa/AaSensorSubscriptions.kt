package app.ft.aa

/**
 * Sensor request evidence independent of car state. Records only public sensor
 * type integers and count, never coordinates, speed, PIN or device identity.
 *
 * The phone, not the head unit, decides which supported sensors it subscribes to.
 */
internal class AaSensorSubscriptions {
    private val received = LinkedHashMap<Int, Int>()

    @Synchronized fun note(sensorType: Int) {
        if (sensorType in 1..26) received[sensorType] = (received[sensorType] ?: 0) + 1
    }

    @Synchronized fun has(sensorType: Int): Boolean = (received[sensorType] ?: 0) > 0

    @Synchronized fun summary(): String = if (received.isEmpty()) "NONE" else
        received.entries.sortedBy { it.key }
            .joinToString(",") { (sensor, count) -> "${name(sensor)}=$count" }

    @Synchronized fun reset() = received.clear()

    private fun name(type: Int): String = when (type) {
        AaProtocol.SENSOR_LOCATION -> "LOCATION"
        AaProtocol.SENSOR_GPS_SATELLITES -> "GPS_SATELLITES"
        3 -> "CAR_SPEED"
        7 -> "PARKING_BRAKE"
        8 -> "GEAR"
        AaProtocol.SENSOR_NIGHT_MODE -> "NIGHT_MODE"
        AaProtocol.SENSOR_DRIVING_STATUS -> "DRIVING_STATUS"
        else -> "SENSOR_$type"
    }
}
