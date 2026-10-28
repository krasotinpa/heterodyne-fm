package fm.heterodyne.app.audio

import fm.heterodyne.app.Journal

enum class AudioFocusChange { Loss, Duck, Transient, Gain }

interface AudioFocusManager {
    fun request(listener: (AudioFocusChange) -> Unit): Boolean
    fun abandon()
}

/** A release action tied to one automatically acquired focus session. */
class AudioFocusLease(val granted: Boolean, private val releaseAction: () -> Unit) {
    private var released = false
    @Synchronized fun release() {
        if (released) return
        released = true
        releaseAction()
    }
}

class AudioFocusController(
    private val manager: AudioFocusManager,
    private val sink: AudioSink,
    private val journal: Journal,
    private val signalVolume: (Float) -> Unit = {},
) {
    private var session: Any? = null
    @Volatile private var focusLevel = 1f
    @Volatile private var effectiveVolume = 1f
    private var silenced = false

    @Synchronized fun setTransitionSilence(value: Boolean) {
        silenced = value
        applyVolume()
    }

    private fun applyVolume() {
        val volume = if (silenced || focusLevel == 0f) 0f else focusLevel
        if (focusLevel == 0f) sink.mute()
        else sink.setVolume(volume)
        effectiveVolume = volume
    }

    private fun setFocusLevel(value: Float) {
        focusLevel = value
        applyVolume()
        signalVolume(value)
    }

    @Volatile private var granted = false

    /** Safe to read on the audio thread without acquiring the controller monitor. */
    val status: String get() = "фокус=${if (granted) "granted" else "denied"}, громкость=$effectiveVolume"

    private var automaticSession: Any? = null

    val playbackActive: Boolean @Synchronized get() = session != null && automaticSession == null

    /** Returns ownership only when this call creates a new focus session. */
    @Synchronized fun acquireForAutostart(): AudioFocusLease? {
        if (session != null) return null
        val token = Any()
        automaticSession = token
        startSession(token)
        return AudioFocusLease(granted) { releaseAutostart(token) }
    }

    @Synchronized private fun releaseAutostart(token: Any) {
        if (session === token && automaticSession === token) stop()
    }

    @Synchronized fun start() {
        automaticSession = null // Playback takes ownership, including an already granted session.
        if (session != null) return
        startSession(Any())
    }

    private fun startSession(token: Any) {
        session = token
        setFocusLevel(1f)
        journal.event("focus volume=1.0")
        // Live radio continues even when the manual request is denied.
        journal.event("focus request")
        granted = manager.request { change -> onFocus(token, change) }
        journal.event("focus request ${if (granted) "granted" else "denied"}")
    }

    @Synchronized fun reclaim() {
        if (session == null) return
        journal.event("focus reclaim")
        stop()
        start()
    }

    @Synchronized fun stop() {
        if (session == null) return
        session = null
        automaticSession = null
        granted = false
        signalVolume(0f)
        manager.abandon()
        journal.event("focus abandon")
    }

    @Synchronized private fun onFocus(token: Any, change: AudioFocusChange) {
        if (session !== token) return
        journal.event("focus ${change.name.uppercase()}")
        when (change) {
            AudioFocusChange.Loss -> {
                setFocusLevel(1f) // Постоянная потеря может прийти после TRANSIENT без GAIN.
                journal.event("focus volume=1.0")
            }
            AudioFocusChange.Duck -> {
                setFocusLevel(0.3f)
                journal.event("focus volume=0.3")
            }
            AudioFocusChange.Transient -> {
                setFocusLevel(0f)
                journal.event("focus mute")
            }
            AudioFocusChange.Gain -> {
                setFocusLevel(1f)
                journal.event("focus volume=1.0")
            }
        }
    }
}
