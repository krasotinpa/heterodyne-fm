package fm.heterodyne.app.audio

import fm.heterodyne.app.Journal

enum class AudioState { Stopped, Starting, Playing, Failed }

data class AudioCounters(
    val bytes: Long = 0,
    val badPackets: Long = 0,
    val realignments: Int = 0,
    val underruns: Int = 0,
    val state: AudioState = AudioState.Stopped,
    val maxReadGapMs: Long = 0,
    val minBufferedFrames: Long = 0,
    val lostLogRecords: Long = 0,
    val logWriteFailures: Long = 0,
)

interface AudioSource {
    fun open()
    fun read(out: ByteArray): Int
    fun badPackets(): Long
    fun close()
}

interface AudioSink {
    fun setVolume(volume: Float)
    fun mute() { setVolume(0f) }
    fun open()
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun play()
    fun bufferedFrames(): Long = 0
    fun underruns(): Int
    fun release()
}

interface UsbAudioPort {
    fun claim()
    fun alt1()
    fun alt0()
    fun release()
}

/** The Module worker owns lifecycle; the optional audio thread owns source and sink IO. */
class AudioPlayback(
    private val modulePresent: () -> Boolean,
    private val source: AudioSource,
    private val sink: AudioSink,
    private val port: UsbAudioPort,
    private val log: Journal,
    private val focus: AudioFocusController? = null,
    private val threadFactory: ((Runnable) -> Thread)? = null,
    private val onMain: (() -> Unit) -> Unit = { it() },
    private val changed: (AudioCounters) -> Unit = {},
) {
    @Volatile var snapshot = AudioCounters(); private set
    private var active = false
    private var claimed = false
    private var alternate = false
    private var sourceOpened = false
    private var sinkOpened = false
    private var receiver: AudioTest? = null
    private var thread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var failed = false
    @Volatile var moduleDetached = false; private set
    private var timedOut = false
    private var lastRead = 0L
    private var minimum = Long.MAX_VALUE
    private var notified = AudioState.Stopped

    fun play(): Boolean {
        if (active || !modulePresent()) return false
        active = true
        stopRequested = false
        failed = false
        moduleDetached = false
        lastRead = 0
        minimum = Long.MAX_VALUE
        safeLog("Audio start")
        publish(AudioCounters(state = AudioState.Starting))
        try {
            claimed = true
            port.claim()
            alternate = true
            port.alt1()
            focus?.let { onMain(it::start) }
            val factory = threadFactory
            if (factory == null) openAudio()
            else {
                thread = factory(Runnable {
                    try {
                        openAudio()
                        while (!stopRequested) readAudio()
                    } catch (_: Throwable) { failed = true }
                    finally { closeAudio() }
                }).also { it.start() }
            }
            return true
        } catch (_: Throwable) {
            finish(failed = true)
            return false
        }
    }

    private fun openAudio() {
        sourceOpened = true
        source.open()
        sinkOpened = true
        sink.open()
        receiver = AudioTest(source, sink)
    }

    private fun readAudio() {
        val loop = checkNotNull(receiver)
        val now = System.nanoTime()
        val gap = if (lastRead == 0L) 0 else (now - lastRead) / 1_000_000
        lastRead = now
        val count = loop.receive()
        val maxGap = maxOf(snapshot.maxReadGapMs, gap)
        if (count < 0) {
            moduleDetached = count == -19
            failed = !moduleDetached
            stopRequested = true
            return
        }
        if (stopRequested) return
        if (loop.playing) minimum = minOf(minimum, loop.minBufferedFrames, sink.bufferedFrames())
        snapshot = snapshot.copy(bytes = loop.bytes, badPackets = loop.badSeen,
            realignments = maxOf(loop.realigned, 0),
            underruns = if (count > 0 || loop.playing) sink.underruns() else snapshot.underruns,
            state = if (loop.playing) AudioState.Playing else AudioState.Starting,
            maxReadGapMs = maxGap,
            minBufferedFrames = if (minimum == Long.MAX_VALUE) 0 else minimum)
    }

    /** Called only by the Module worker; no journal or UI callback runs on the audio thread. */
    fun receive() {
        if (!active || timedOut) return
        val running = thread
        if (running != null) {
            if (!running.isAlive) finish(detached = moduleDetached, failed = failed)
            else notifyState(snapshot)
            return
        }
        try {
            readAudio()
            if (stopRequested) finish(detached = moduleDetached, failed = failed)
            else publish(snapshot)
        } catch (_: Throwable) { finish(failed = true) }
    }

    fun acknowledgeDetach() { moduleDetached = false }

    fun onModuleDetached(): Boolean { moduleDetached = true; return finish(detached = true) }

    /** False means the shared descriptor must remain open, even if the thread exits later. */
    fun stop(): Boolean = finish()

    private fun closeAudio() {
        if (sourceOpened) try { source.close() } catch (_: Throwable) { failed = true }
        sourceOpened = false
        if (sinkOpened) try { sink.release() } catch (_: Throwable) { failed = true }
        sinkOpened = false
        receiver = null
    }

    private fun finish(detached: Boolean = moduleDetached, failed: Boolean = false): Boolean {
        if (timedOut) return false
        if (!active) return true
        stopRequested = true
        val running = thread
        if (running != null) {
            try { running.join(2000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            if (running.isAlive) {
                timedOut = true
                safeLog("Audio stop timeout")
                publish(snapshot.copy(state = AudioState.Failed))
                focus?.let { onMain(it::stop) }
                return false
            }
            thread = null
        }
        active = false
        receiver?.stop = true
        receiver = null
        var cleanupFailed = failed || this.failed
        val releases = mutableListOf<() -> Unit>()
        focus?.let { releases.add { onMain(it::stop) } }
        if (sourceOpened) releases.add(source::close)
        if (alternate && !detached && !moduleDetached) releases.add(port::alt0)
        if (claimed) releases.add(port::release)
        if (sinkOpened) releases.add(sink::release)
        sourceOpened = false
        alternate = false
        claimed = false
        sinkOpened = false
        for (release in releases) {
            try { release() } catch (_: Throwable) { cleanupFailed = true }
        }
        if (cleanupFailed) safeLog("Audio error")
        safeLog("Audio stop")
        publish(snapshot.copy(state = if (cleanupFailed) AudioState.Failed else AudioState.Stopped))
        return true
    }

    private fun publish(next: AudioCounters) {
        snapshot = next
        notifyState(next)
    }

    private fun notifyState(next: AudioCounters) {
        if (notified != next.state) { notified = next.state; changed(next) }
    }

    private fun safeLog(event: String) {
        try { log.event(event) } catch (_: Exception) {
            // Journal failures must never prevent audio cleanup.
        }
    }
}
