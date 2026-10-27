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

    private var granted = false

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
        sink.setVolume(1f)
        signalVolume(1f)
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
                sink.setVolume(1f)
                signalVolume(1f) // Постоянная потеря может прийти после TRANSIENT без GAIN.
                journal.event("focus volume=1.0")
            }
            AudioFocusChange.Duck -> {
                sink.setVolume(0.3f)
                signalVolume(0.3f)
                journal.event("focus volume=0.3")
            }
            AudioFocusChange.Transient -> {
                sink.mute()
                signalVolume(0f)
                journal.event("focus mute")
            }
            AudioFocusChange.Gain -> {
                sink.setVolume(1f)
                signalVolume(1f)
                journal.event("focus volume=1.0")
            }
        }
    }
}
