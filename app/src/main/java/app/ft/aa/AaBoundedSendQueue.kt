package app.ft.aa

import java.io.Closeable
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-AA-session bounded main-thread offload. A stale session cannot leave
 * queued writes executing after its socket closes.
 *
 * Control and vehicle-sensor messages are protected. Under overload an older
 * noncritical offload is displaced first. If only critical entries remain,
 * caller must fail the session rather than claim a dropped GPS/control write.
 */
internal class AaBoundedSendQueue(
    private val capacity: Int = 128,
    private val onFailure: (Throwable) -> Unit = {},
    private val onDropped: (Int) -> Unit = {}
) : Closeable {
    private data class Task(val priority: Boolean, val run: () -> Unit)
    private val pending = LinkedBlockingDeque<Task>(capacity)
    private val lock = Any()
    @Volatile private var closed = false
    private val dropped = AtomicInteger(0)
    private val worker = Thread({
        try {
            while (!closed) {
                val task = pending.take()
                if (!closed) try {
                    task.run()
                } catch (e: Throwable) {
                    if (!closed) onFailure(e)
                }
            }
        } catch (_: InterruptedException) {
            // Close stops blocked workers and cancels pending actions.
        }
    }, "ft-aa-session-send").apply { isDaemon = true; start() }

    init { require(capacity >= 1) }

    fun offer(priority: Boolean, action: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return false
        val task = Task(priority, action)
        if (pending.offerLast(task)) return true
        if (priority) {
            val older = pending.firstOrNull { !it.priority }
            if (older != null && pending.remove(older) && pending.offerLast(task)) {
                onDropped(dropped.incrementAndGet())
                return true
            }
        }
        if (!priority) onDropped(dropped.incrementAndGet())
        false
    }

    fun queued(): Int = pending.size
    fun isStopped(): Boolean = closed

    override fun close() = synchronized(lock) {
        closed = true
        pending.clear()
        worker.interrupt()
    }
}
