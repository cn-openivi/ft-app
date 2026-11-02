package app.ft.aa

/**
 * Tracks Android Auto audio channels that have sent AV_START.
 *
 * A channel can close without AV_STOP. The caller must release its audio bus
 * lane once when that happens; otherwise an old SPEECH lane can stay active
 * and continue ducking MEDIA on the next connection.
 */
internal class AaAudioChannelRegistry {
    private val active = LinkedHashMap<Int, Int>()

    @Synchronized fun start(channel: Int, session: Int): Boolean =
        active.put(channel, session) == null

    @Synchronized fun session(channel: Int): Int = active[channel] ?: 0

    @Synchronized fun isActive(channel: Int): Boolean = active.containsKey(channel)

    @Synchronized fun stop(channel: Int): Boolean = active.remove(channel) != null

    @Synchronized fun stopAll(): Set<Int> =
        active.keys.toSet().also { active.clear() }
}
