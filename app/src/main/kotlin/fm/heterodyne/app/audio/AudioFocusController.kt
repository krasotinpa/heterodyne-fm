package fm.heterodyne.app.audio

enum class AudioFocusChange { Loss, Duck, Transient, Gain }

interface AudioFocusManager {
    fun request(listener: (AudioFocusChange) -> Unit): Boolean
    fun abandon()
}

class AudioFocusController(private val manager: AudioFocusManager, private val sink: AudioSink) {
    private var session: Any? = null

    @Synchronized fun start() {
        if (session != null) return
        val token = Any()
        session = token
        sink.setVolume(1f)
        // Live radio continues even when the request is denied.
        manager.request { change -> onFocus(token, change) }
    }

    @Synchronized fun stop() {
        if (session == null) return
        session = null
        manager.abandon()
    }

    @Synchronized private fun onFocus(token: Any, change: AudioFocusChange) {
        if (session !== token) return
        when (change) {
            AudioFocusChange.Loss -> sink.setVolume(1f) // a permanent loss may follow a transient one, with no GAIN
            AudioFocusChange.Duck -> sink.setVolume(0.3f)
            AudioFocusChange.Transient -> sink.mute()
            AudioFocusChange.Gain -> sink.setVolume(1f)
        }
    }
}
