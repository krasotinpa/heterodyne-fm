package fm.heterodyne.app.module

import java.util.concurrent.atomic.AtomicLong

/** An opening captures this token before storage IO; only user actions invalidate it. */
class AutostartGeneration {
    private val generation = AtomicLong()
    fun capture(): Long = generation.get()
    fun cancel() { generation.incrementAndGet() }
    fun isCancelled(token: Long): Boolean = token != generation.get()
}
