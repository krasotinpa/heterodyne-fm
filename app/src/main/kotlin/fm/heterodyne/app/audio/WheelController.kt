package fm.heterodyne.app.audio

import fm.heterodyne.app.module.Direction
import fm.heterodyne.app.stations.StationEntry

/** Runs on the caller's event loop. Returns a cancellation action for each timer. */
fun interface WheelScheduler {
    fun after(delayMs: Long, action: () -> Unit): () -> Unit
}

class WheelController(
    private val scheduler: WheelScheduler,
    private val select: (StationEntry) -> Unit,
    private val seek: (Direction) -> Unit,
) {
    private var entries = emptyList<StationEntry>()
    private var current: StationEntry? = null
    private var generation = 0L
    private var held = false
    private var cancel: (() -> Unit)? = null
    var cursor: StationEntry? = null
        private set

    fun update(entries: List<StationEntry>, current: StationEntry?) {
        if (this.entries != entries) {
            generation++
            cancel?.invoke()
            cancel = null
            this.entries = entries.toList()
            cursor = current
        } else if (cancel == null && this.current != current) cursor = current
        this.current = current
    }

    fun keyDown(direction: Direction, longPress: Boolean, repeatCount: Int) {
        if (longPress) longPress(direction)
        else if (repeatCount == 0) {
            if (direction == Direction.Up) next() else previous()
        }
    }

    fun longPress(direction: Direction) {
        if (held) return
        held = true
        generation++
        cancel?.invoke()
        cancel = null
        cursor = current
        seek(direction)
    }

    fun keyUp() { held = false }

    fun close() {
        generation++
        cancel?.invoke()
        cancel = null
        held = false
    }

    fun next() = move(1)
    fun previous() = move(-1)

    private fun move(delta: Int) {
        if (held || entries.isEmpty()) return
        val index = entries.indexOfFirst { it.band == cursor?.band && it.frequencyKhz == cursor?.frequencyKhz }
        cursor = entries[if (index < 0) { if (delta > 0) 0 else entries.lastIndex }
            else (index + delta + entries.size) % entries.size]
        generation++
        cancel?.invoke()
        val target = checkNotNull(cursor)
        val token = generation
        cancel = scheduler.after(1000) {
            if (token == generation) { cancel = null; select(target) }
        }
    }
}
