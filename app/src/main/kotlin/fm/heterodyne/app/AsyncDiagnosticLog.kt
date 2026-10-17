package fm.heterodyne.app

import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Only this low-priority worker touches the persistent journal. Producers never wait for it. */
class AsyncDiagnosticLog(
    private val create: () -> DiagnosticLog,
    private val capacity: Int = 1024,
    private val beforeWrite: () -> Unit = {},
    priority: () -> Unit = { Thread.currentThread().priority = Thread.MIN_PRIORITY },
) : Journal, AutoCloseable {
    private data class Record(val frame: Boolean, val error: (Exception) -> Unit = {}, val write: (DiagnosticLog) -> Unit)
    init { require(capacity > 0) }
    private val queue = ConcurrentLinkedDeque<Record>()
    private val size = AtomicInteger()
    private val lost = AtomicLong()
    private val failures = AtomicLong()
    val lostRecords get() = lost.get()
    val writeFailures get() = failures.get()
    @Volatile private var closed = false
    private val worker = Thread({
        priority()
        var disk: DiagnosticLog? = try { create() } catch (_: Exception) { failures.incrementAndGet(); null }
        var reported = 0L
        while (!closed || queue.isNotEmpty()) {
            val record = queue.pollFirst()
            if (record == null) {
                val total = lost.get()
                if (total > reported) {
                    try {
                        val log = disk ?: create().also { disk = it }
                        log.event("потеряно ${total - reported}")
                        reported = total
                    } catch (_: Exception) { failures.incrementAndGet() }
                }
                LockSupport.parkNanos(1_000_000)
                continue
            }
            size.decrementAndGet()
            try {
                val log = disk ?: create().also { disk = it }
                val total = lost.get()
                if (total > reported) { log.event("потеряно ${total - reported}"); reported = total }
                beforeWrite()
                record.write(log)
            } catch (failure: Exception) { failures.incrementAndGet(); record.error(failure) }
        }
    }, "Journal").apply { isDaemon = true; start() }

    private fun offer(record: Record) {
        if (closed) return
        while (true) {
            val count = size.get()
            if (count < capacity && size.compareAndSet(count, count + 1)) {
                queue.addLast(record)
                LockSupport.unpark(worker)
                return
            }
            if (count < capacity) continue
            val oldestFrame = queue.firstOrNull { it.frame }
            if (oldestFrame == null) { lost.incrementAndGet(); return }
            if (queue.remove(oldestFrame)) { size.decrementAndGet(); lost.incrementAndGet() }
        }
    }

    override fun tx(frame: ByteArray) { val copy = frame.copyOf(); offer(Record(true) { it.tx(copy) }) }
    override fun rx(frame: ByteArray) { val copy = frame.copyOf(); offer(Record(true) { it.rx(copy) }) }
    override fun event(text: String) = offer(Record(false) { it.event(text) })
    fun buildVersion(version: String) = event("build=$version")

    // Read barriers are requested outside the audio path. Retry admission without losing the barrier.
    private fun <T> read(action: (DiagnosticLog) -> T): T {
        check(!closed)
        val result = CompletableFuture<T>()
        val barrier = Record(false, { result.completeExceptionally(it) }) { log ->
            try { result.complete(action(log)) } catch (failure: Exception) { result.completeExceptionally(failure) }
        }
        while (true) {
            val count = size.get()
            if (count < capacity && size.compareAndSet(count, count + 1)) { queue.addLast(barrier); break }
            Thread.yield()
        }
        LockSupport.unpark(worker)
        return result.get()
    }
    fun readTail(maxBytes: Int) = read { it.readTail(maxBytes) }
    fun copyTo(output: OutputStream) = read { it.copyTo(output) }
    override fun close() { closed = true; LockSupport.unpark(worker); worker.join() }
}
