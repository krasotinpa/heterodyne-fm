package fm.heterodyne.app.module

import fm.heterodyne.app.Journal
import fm.heterodyne.app.audio.AudioFocusLease

/** Module-worker owned. Opening the application is separate from SessionRestart's return trigger. */
class LaunchAutostart(
    private val module: ModuleController,
    private val log: Journal,
    private val clock: () -> Long,
    private val requestFocus: ((AudioFocusLease?) -> Unit) -> Unit,
    private val startPlayback: ((Boolean) -> Unit) -> Unit,
    private val noFocusChanged: (Boolean) -> Unit = {},
    private val cancelled: () -> Boolean = { false },
    private val isPlaying: () -> Boolean = { false },
) {
    private enum class Phase { Done, Pending, Focus, Probe, Selecting, Starting }
    @Volatile private var phase = Phase.Done
    val startingPlayback: Boolean get() = phase == Phase.Starting
    private var station: TuneTarget? = null
    private var lastAttempt: Long? = null
    private var probeAt = 0L
    private var revision = 0L
    private var transitionAt = 0L
    private var generation = 0L
    private var focusLease: AudioFocusLease? = null
    private var openedAt = 0L

    fun open(enabled: Boolean, station: TuneTarget?) {
        if (isPlaying()) { event("already-playing"); return }
        cancel()
        noFocusChanged(false)
        this.station = station?.takeIf { enabled && isSelectableFrequency(it.band, it.frequencyKhz) }
        if (this.station == null) { event("skipped"); return }
        if (lastAttempt?.let { clock() - it < 30000 } == true) { event("rate-limited"); return }
        openedAt = clock()
        phase = Phase.Pending
        tick()
    }

    fun cancel() {
        generation++
        if (phase == Phase.Probe || phase == Phase.Selecting) module.cancelSelection()
        if (phase != Phase.Done) event("cancelled")
        phase = Phase.Done
        releaseOwnedFocus()
        noFocusChanged(false)
    }

    /** Also called before the Module's internal ticks, since cancellation may arrive during a USB read. */
    fun cancelIfRequested(): Boolean {
        if (!cancelled()) return false
        if (phase != Phase.Done) cancel()
        return true
    }

    /** Guard the Module's internal dispatch too, including when playback starts during a USB read. */
    fun guardPendingWork(): Boolean {
        if (cancelIfRequested()) return true
        if (phase != Phase.Done && phase != Phase.Starting && isPlaying()) {
            finish("already-playing")
            return true
        }
        return false
    }

    fun tick() {
        if (guardPendingWork()) return
        if (phase == Phase.Done) return
        if (phase == Phase.Pending && clock() - openedAt >= 30000) { finish("module-timeout"); return }
        if (module.state != ConnectionState.Observing) {
            if (phase != Phase.Pending) finish("disconnected")
            return
        }
        when (phase) {
            Phase.Pending -> {
                lastAttempt = clock()
                phase = Phase.Focus
                val token = generation
                event("focus-request")
                requestFocus { lease ->
                    // A late acquisition still owns its own session, and must release it safely.
                    if (token != generation || phase != Phase.Focus) { lease?.release(); return@requestFocus }
                    focusLease = lease
                    if (guardPendingWork()) return@requestFocus
                    if (lease == null) finish("already-playing")
                    else if (!lease.granted) { finish("no-focus"); noFocusChanged(true) }
                    else if (module.state != ConnectionState.Observing) finish("disconnected")
                    else {
                        revision = module.stationRevision
                        probeAt = clock()
                        phase = Phase.Probe
                        if (module.checkConnection() != TuneResult.Sent) finish("probe-not-sent")
                    }
                }
            }
            Phase.Probe -> {
                if (clock() - probeAt >= 3000) { finish("probe-timeout"); return }
                if (module.stationRevision == revision) return
                if (module.snapshot.confirmed == station) play("already-selected")
                else {
                    phase = Phase.Selecting
                    transitionAt = clock()
                    val goal = checkNotNull(station)
                    when (module.select(goal.band, goal.frequencyKhz, bandSettleMillis = 2000)) {
                        TuneResult.Sent, TuneResult.RateLimited -> event("transition")
                        else -> finish("select-failed")
                    }
                }
            }
            Phase.Selecting -> {
                if (clock() - transitionAt >= 3000) { finish("transition-timeout"); return }
                if (module.snapshot.target == null) {
                    if (module.snapshot.confirmed == station && module.transitionState == TransitionState.Idle)
                        play("selected")
                    else finish("transition-timeout")
                }
            }
            else -> Unit
        }
    }

    private fun play(result: String) {
        phase = Phase.Starting
        val token = generation
        try {
            startPlayback { started ->
                // The adapter completes on the Module worker after playback takes ownership.
                if (token != generation || phase != Phase.Starting) return@startPlayback
                if (cancelIfRequested()) return@startPlayback
                if (started) {
                    focusLease = null
                    phase = Phase.Done
                    event(result)
                } else finish("start-refused")
            }
        } catch (_: Exception) { finish("start-refused") }
    }
    private fun releaseOwnedFocus() {
        val lease = focusLease ?: return
        focusLease = null
        lease.release()
    }
    private fun finish(result: String) {
        // The launch deadline includes rate-limit waits; it must discard any still queued target.
        if (phase == Phase.Probe || phase == Phase.Selecting) module.cancelSelection()
        phase = Phase.Done
        releaseOwnedFocus()
        event(result)
    }
    private fun event(result: String) {
        try { log.event("autostart reason=application-open result=$result") } catch (_: Exception) { /* Passive Journal. */ }
    }
}
