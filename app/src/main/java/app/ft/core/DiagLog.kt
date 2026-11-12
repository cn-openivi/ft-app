package app.ft.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class DiagEntry(val time: Long, val tag: String, val text: String, val level: Int)

object DiagLog {
    private const val MAX = 800
    private const val FILE_LIMIT = 4L * 1024 * 1024
    private const val FILES_KEPT = 4
    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    val entries: StateFlow<List<DiagEntry>> = _entries
    private sealed interface Work {
        data class Line(val entry: DiagEntry) : Work
        class Fence(val done: CountDownLatch = CountDownLatch(1)) : Work {
            @Volatile var durable = false
        }
    }
    private val pending = LinkedBlockingQueue<Work>(5000)
    private val accepted = AtomicLong(0)
    private val persisted = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val ioErrors = AtomicLong(0)
    private val incompleteFlushes = AtomicLong(0)
    @Volatile private var dir: File? = null
    private var writer: Thread? = null

    fun attach(folder: File?) {
        if (folder == null || dir != null) return
        runCatching { folder.mkdirs() }
        dir = folder
        writer = Thread {
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val levels = mapOf(Log.DEBUG to "D", Log.INFO to "I", Log.WARN to "W", Log.ERROR to "E")
            val lines = ArrayList<DiagEntry>(501)
            fun writeBatch(): Boolean {
                if (lines.isEmpty()) return true
                val count = lines.size
                val written = runCatching {
                    val file = current() ?: error("log directory not attached")
                    file.appendText(lines.joinToString("") { e ->
                        "${stamp.format(Date(e.time))} ${levels[e.level] ?: "I"} ${e.tag}: ${e.text}\n"
                    })
                }.onFailure {
                    ioErrors.incrementAndGet()
                    Log.println(Log.ERROR, "FT/DiagLog", "log file append failed: ${it.javaClass.simpleName}")
                }.isSuccess
                if (written) persisted.addAndGet(count.toLong())
                lines.clear()
                return written
            }
            fun syncFiles(): Boolean = runCatching {
                // A drained queue alone says nothing about a write in progress.
                // Force all retained file descriptors before acknowledging a ZIP snapshot.
                files().forEach { file -> FileOutputStream(file, true).use { it.fd.sync() } }
            }.onFailure {
                ioErrors.incrementAndGet()
                Log.println(Log.ERROR, "FT/DiagLog", "log fsync failed: ${it.javaClass.simpleName}")
            }.isSuccess
            while (true) {
                val first = runCatching { pending.take() }.getOrNull() ?: break
                val batch = ArrayList<Work>(501).apply { add(first) }
                pending.drainTo(batch, 500)
                var batchOk = true
                for (item in batch) when (item) {
                    is Work.Line -> lines.add(item.entry)
                    is Work.Fence -> {
                        batchOk = writeBatch() && batchOk
                        // Fence is ordered after all preceding LogWork; sync happens
                        // before signaling, never before the writer appends.
                        item.durable = batchOk && syncFiles()
                        item.done.countDown()
                        batchOk = true
                    }
                }
                writeBatch()
            }
        }.apply { isDaemon = true; name = "ft-log-file"; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun current(): File? {
        val d = dir ?: return null
        val now = File(d, "ft.log")
        if (now.exists() && now.length() > FILE_LIMIT) {
            for (i in FILES_KEPT - 1 downTo 1) {
                val from = File(d, "ft.$i.log")
                if (from.exists()) from.renameTo(File(d, "ft.${i + 1}.log"))
            }
            now.renameTo(File(d, "ft.1.log"))
            File(d, "ft.${FILES_KEPT + 1}.log").delete()
        }
        return now
    }

    fun i(tag: String, text: String) = add(tag, text, Log.INFO)
    fun w(tag: String, text: String) = add(tag, text, Log.WARN)
    fun d(tag: String, text: String) = add(tag, text, Log.DEBUG)
    fun e(tag: String, text: String, t: Throwable? = null) =
        add(tag, if (t != null) "$text: ${t.javaClass.simpleName} ${t.message ?: ""}\n${trace(t)}" else text, Log.ERROR)

    // Unstructured protobuf/ICCOA payloads may encode exact coordinates or destinations.
    // Hide them too unless the owner explicitly opts into location-bearing diagnostics.
    private fun rawPayloadEnabled(): Boolean =
        runCatching { app.ft.FTApp.instance.prefs.vehicleCoordinateLogging }.getOrDefault(false)

    fun rx(tag: String, what: String, data: ByteArray) = add(tag,
        if (rawPayloadEnabled()) "RX $what  ${Bytes.hex(data, PAYLOAD_LIMIT)}"
        else "RX $what bytes=${data.size} payload=OMITTED", Log.DEBUG)
    fun tx(tag: String, what: String, data: ByteArray) = add(tag,
        if (rawPayloadEnabled()) "TX $what  ${Bytes.hex(data, PAYLOAD_LIMIT)}"
        else "TX $what bytes=${data.size} payload=OMITTED", Log.DEBUG)

    fun trace(t: Throwable, maxFrames: Int = 40): String {
        val sb = StringBuilder()
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth < 6) {
            if (depth > 0) sb.append("Caused by: ${cause.javaClass.name}: ${cause.message ?: ""}\n")
            cause.stackTrace.take(maxFrames).forEach { sb.append("    at ").append(it).append('\n') }
            if (cause.stackTrace.size > maxFrames) sb.append("    … ${cause.stackTrace.size - maxFrames} more\n")
            cause = cause.cause.takeIf { it !== cause }
            depth++
        }
        return sb.toString().trimEnd()
    }

    fun crash(thread: Thread, t: Throwable) {
        val text = "FT crashed on thread '${thread.name}': ${t.javaClass.name}: ${t.message ?: ""}\n${trace(t, 80)}"
        Log.println(Log.ERROR, "FT/Crash", text)
        val d = dir ?: return
        runCatching {
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val queued = ArrayList<Work>().also { pending.drainTo(it) }
            // A crash cannot report success for a flush fence removed from the queue.
            queued.filterIsInstance<Work.Fence>().forEach { it.done.countDown() }
            val lines = queued.filterIsInstance<Work.Line>()
            File(d, "ft.log").appendText(
                lines.joinToString("") { e ->
                    "${stamp.format(Date(e.entry.time))} ${levelName(e.entry.level)} ${e.entry.tag}: ${e.entry.text}\n"
                } + "${stamp.format(Date())} E Crash: $text\n"
            )
            persisted.addAndGet(lines.size.toLong())
            FileOutputStream(File(d, "ft.log"), true).use { it.fd.sync() }
        }.onFailure {
            ioErrors.incrementAndGet()
        }
    }

    fun files(): List<File> {
        val d = dir ?: return emptyList()
        return (FILES_KEPT + 1 downTo 1).map { File(d, "ft.$it.log") }.filter { it.exists() } + listOfNotNull(File(d, "ft.log").takeIf { it.exists() })
    }

    /**
     * Returns true only if the writer has processed and fsynced its ordered fence.
     * A false result must be recorded in the report, not silently treated as success.
     */
    fun flush(timeoutMs: Long = 1500): Boolean {
        val thread = writer
        if (dir == null || thread?.isAlive != true) {
            incompleteFlushes.incrementAndGet()
            return false
        }
        val budget = timeoutMs.coerceAtLeast(1L)
        val startedNs = System.nanoTime()
        val fence = Work.Fence()
        val enqueued = try {
            pending.offer(fence, budget, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            incompleteFlushes.incrementAndGet()
            return false
        }
        val remainingNs = (budget * 1_000_000L - (System.nanoTime() - startedNs)).coerceAtLeast(0L)
        val completed = try {
            fence.done.await(remainingNs, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val ok = completed && fence.durable && dropped.get() == 0L && ioErrors.get() == 0L
        if (!ok) incompleteFlushes.incrementAndGet()
        return ok
    }

    /** Counts only. No payloads, coordinates, package names or user text. */
    fun completeness(): String =
        "accepted=${accepted.get()} persisted=${persisted.get()} pending=${pending.size} " +
        "dropped=${dropped.get()} ioErrors=${ioErrors.get()} incompleteFlushes=${incompleteFlushes.get()} " +
        "writerAlive=${writer?.isAlive == true} " +
        "status=${if (dropped.get() == 0L && ioErrors.get() == 0L && incompleteFlushes.get() == 0L) "NO_RECORDED_LOSS" else "INCOMPLETE"}"

    private fun levelName(level: Int) = when (level) {
        Log.DEBUG -> "D"; Log.WARN -> "W"; Log.ERROR -> "E"; else -> "I"
    }

    private const val PAYLOAD_LIMIT = 1024

    private fun add(tag: String, text: String, level: Int) {
        Log.println(level, "FT/$tag", text)
        val entry = DiagEntry(System.currentTimeMillis(), tag, text, level)
        _entries.update { (it + entry).takeLast(MAX) }
        if (dir != null) {
            if (pending.offer(Work.Line(entry))) accepted.incrementAndGet()
            else {
                val lost = dropped.incrementAndGet()
                // Exponential reporting bounds logcat spam under sustained overflow.
                if (lost == 1L || (lost and (lost - 1L)) == 0L)
                    Log.println(Log.WARN, "FT/DiagLog", "file-log overflow dropped=$lost; report marked incomplete")
            }
        }
    }

    fun clear() = _entries.update { emptyList() }
}
