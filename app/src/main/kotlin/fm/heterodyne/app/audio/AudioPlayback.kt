package fm.heterodyne.app.audio

import fm.heterodyne.app.DiagnosticLog

enum class AudioState { Stopped, Starting, Playing, Failed }

data class AudioCounters(
    val bytes: Long = 0,
    val badPackets: Long = 0,
    val realignments: Int = 0,
    val underruns: Int = 0,
    val state: AudioState = AudioState.Stopped,
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
    fun underruns(): Int
    fun release()
}

interface UsbAudioPort {
    fun claim()
    fun alt1()
    fun alt0()
    fun release()
}

/** One worker owns the session; readers can retain an immutable counters snapshot. */
class AudioPlayback(
    private val modulePresent: () -> Boolean,
    private val source: AudioSource,
    private val sink: AudioSink,
    private val port: UsbAudioPort,
    private val log: DiagnosticLog,
    private val focus: AudioFocusController? = null,
    private val changed: (AudioCounters) -> Unit = {},
) {
    @Volatile var snapshot = AudioCounters(); private set
    private var active = false
    private var claimed = false
    private var alternate = false
    private var sourceOpened = false
    private var sinkOpened = false
    private var receiver: AudioTest? = null

    fun play(): Boolean {
        if (active || !modulePresent()) return false
        active = true
        safeLog("Audio start")
        publish(AudioCounters(state = AudioState.Starting))
        try {
            claimed = true
            port.claim()
            alternate = true
            port.alt1()
            sourceOpened = true
            source.open()
            sinkOpened = true
            sink.open()
            focus?.start()
            receiver = AudioTest(source, sink)
            return true
        } catch (_: Throwable) {
            finish(failed = true)
            return false
        }
    }

    fun receive() {
        if (!active) return
        try {
            val loop = checkNotNull(receiver)
            val count = loop.receive()
            if (count == 0) return
            if (count < 0) {
                // ENODEV can arrive before the detach broadcast.
                finish(detached = count == -19, failed = count != -19)
                return
            }
            publish(AudioCounters(loop.bytes, loop.badSeen, maxOf(loop.realigned, 0), sink.underruns(),
                if (loop.playing) AudioState.Playing else AudioState.Starting))
        } catch (_: Throwable) { finish(failed = true) }
    }

    fun onModuleDetached() { finish(detached = true) }

    fun stop() { finish() }

    private fun finish(detached: Boolean = false, failed: Boolean = false) {
        if (!active) return
        active = false
        receiver?.stop = true
        receiver = null
        var cleanupFailed = failed
        val releases = mutableListOf<() -> Unit>()
        focus?.let { releases.add(it::stop) }
        if (sourceOpened) releases.add(source::close)
        if (alternate && !detached) releases.add(port::alt0)
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
    }

    private fun publish(next: AudioCounters) {
        val previous = snapshot.state
        snapshot = next
        if (previous != next.state) changed(next)
    }

    private fun safeLog(event: String) {
        try { log.event(event) } catch (_: Exception) {
            // Journal failures must never prevent audio cleanup.
        }
    }
}
