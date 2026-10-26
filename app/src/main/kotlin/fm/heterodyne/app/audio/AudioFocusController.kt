package fm.heterodyne.app.audio

import fm.heterodyne.app.Journal

enum class AudioFocusChange { Loss, Duck, Transient, Gain }

interface AudioFocusManager {
    fun request(listener: (AudioFocusChange) -> Unit): Boolean
    fun abandon()
}

class AudioFocusController(
    private val manager: AudioFocusManager,
    private val sink: AudioSink,
    private val journal: Journal,
) {
    private var session: Any? = null

    @Synchronized fun start() {
        if (session != null) return
        val token = Any()
        session = token
        sink.setVolume(1f)
        journal.event("focus volume=1.0")
        // Live radio continues even when the request is denied.
        journal.event("focus request")
        val granted = manager.request { change -> onFocus(token, change) }
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
        manager.abandon()
        journal.event("focus abandon")
    }

    @Synchronized private fun onFocus(token: Any, change: AudioFocusChange) {
        if (session !== token) return
        journal.event("focus ${change.name.uppercase()}")
        when (change) {
            AudioFocusChange.Loss -> {
                sink.setVolume(1f) // a permanent loss may follow a transient one, with no GAIN
                journal.event("focus volume=1.0")
            }
            AudioFocusChange.Duck -> {
                sink.setVolume(0.3f)
                journal.event("focus volume=0.3")
            }
            AudioFocusChange.Transient -> {
                sink.mute()
                journal.event("focus mute")
            }
            AudioFocusChange.Gain -> {
                sink.setVolume(1f)
                journal.event("focus volume=1.0")
            }
        }
    }
}
