package fm.heterodyne.app.ui

/** Monotonic milliseconds and GNSS speed in metres per second. No data means parked. */
data class DrivingState(
    val driving: Boolean = false,
    private val fastSince: Long? = null,
    private val slowSince: Long? = null,
) {
    fun update(speed: Float?, nowMs: Long): DrivingState {
        if (speed == null || !speed.isFinite() || speed < 0f) return DrivingState()
        return when {
            speed >= 2f -> {
                val since = fastSince ?: nowMs
                DrivingState(driving || nowMs - since >= 3000, fastSince = since)
            }
            speed < 0.5f -> {
                val since = slowSince ?: nowMs
                DrivingState(driving && nowMs - since < 5000, slowSince = since)
            }
            else -> DrivingState(driving)
        }
    }
}

interface DrivingSpeedSource {
    fun start()
    fun read(): Float?
    fun stop()
}

/** Foreground owner polls on the main Handler; events only enqueue Journal writes. */
class DrivingMonitor(
    private val source: DrivingSpeedSource,
    private val clock: () -> Long,
    private val update: (Boolean) -> Unit,
    private val event: (String) -> Unit,
) {
    private var state = DrivingState()
    private var started = false

    fun start() {
        if (started) return
        source.start()
        started = true
        poll()
    }

    fun poll() {
        if (!started) return
        val speed = source.read()
        apply(speed, if (speed == null) "no GNSS data" else "GNSS hysteresis")
    }

    fun stop() {
        if (!started) return
        source.stop()
        started = false
        apply(null, "foreground stopped")
    }

    private fun apply(speed: Float?, reason: String) {
        val next = state.update(speed, clock())
        if (next.driving != state.driving) {
            update(next.driving)
            event("driving=${next.driving}, speed=$speed m/s, reason=$reason")
        }
        state = next
    }
}
