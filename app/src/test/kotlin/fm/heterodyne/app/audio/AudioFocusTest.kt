package fm.heterodyne.app.audio

import fm.heterodyne.app.DiagnosticLog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioFocusTest {
    @get:Rule val temporary = TemporaryFolder()
    private val manager = FocusManagerFake()
    private val sink = FocusSinkFake()
    private fun playback() = AudioPlayback({ true }, object : AudioSource {
        override fun open() {}
        override fun read(out: ByteArray): Int { out.fill(0); return out.size }
        override fun badPackets() = 0L
        override fun close() {}
    }, sink, object : UsbAudioPort {
        override fun claim() {}
        override fun alt1() {}
        override fun alt0() {}
        override fun release() {}
    }, DiagnosticLog(temporary.newFolder()), focus = AudioFocusController(manager, sink))

    @Test fun permanentLossKeepsSoundAndStream() {
        val audio = playback()
        assertTrue(audio.play())
        manager.signal(AudioFocusChange.Loss)
        repeat(20) { audio.receive() }
        assertEquals(1f, sink.level)
        assertEquals(AudioState.Playing, audio.snapshot.state)
        assertTrue(sink.written > 0)
    }

    @Test fun permanentLossAfterTransientOrDuckRestoresSound() {
        for (first in listOf(AudioFocusChange.Transient, AudioFocusChange.Duck)) {
            val audio = playback()
            audio.play()
            manager.signal(first)
            manager.signal(AudioFocusChange.Loss)
            assertEquals(1f, sink.level)
            audio.stop()
        }
    }

    @Test fun duckAndGainRestoreVolume() {
        val audio = playback()
        audio.play()
        manager.signal(AudioFocusChange.Duck)
        assertEquals(0.3f, sink.level)
        manager.signal(AudioFocusChange.Gain)
        assertEquals(1f, sink.level)
    }

    @Test fun transientMutesWhileWritesContinueAndGainRestoresSound() {
        val audio = playback()
        audio.play()
        repeat(20) { audio.receive() }
        val before = sink.written
        manager.signal(AudioFocusChange.Transient)
        assertEquals(0f, sink.level)
        audio.receive()
        assertTrue(sink.written > before)
        assertEquals(AudioState.Playing, audio.snapshot.state)
        manager.signal(AudioFocusChange.Gain)
        assertEquals(1f, sink.level)
    }

    @Test fun noSignalKeepsUnityAndRequestsBeforeSound() {
        val audio = playback()
        manager.onRequest = {
            assertFalse(sink.played)
            assertEquals(0, sink.written)
        }
        audio.play()
        assertEquals(1, manager.requests)
        repeat(20) { audio.receive() }
        assertEquals(1f, sink.level)
        assertTrue(sink.played)
    }

    @Test fun stopAbandonsOnceAndIgnoresLateSignals() {
        val audio = playback()
        audio.play()
        val late = manager.listener
        audio.stop()
        audio.stop()
        assertEquals(1, manager.abandons)
        late(AudioFocusChange.Duck)
        assertEquals(1f, sink.level)
    }

    @Test fun deniedFocusStillPlays() {
        manager.granted = false
        val audio = playback()
        assertTrue(audio.play())
        repeat(20) { audio.receive() }
        assertEquals(AudioState.Playing, audio.snapshot.state)
        assertEquals(1f, sink.level)
        audio.stop()
        assertEquals(1, manager.abandons)
    }

    @Test fun newSessionResetsVolumeAndIgnoresOldListener() {
        val audio = playback()
        audio.play()
        val old = manager.listener
        manager.signal(AudioFocusChange.Transient)
        audio.stop()
        audio.play()
        old(AudioFocusChange.Duck)
        assertEquals(1f, sink.level)
        assertEquals(2, manager.requests)
    }
}

private class FocusManagerFake : AudioFocusManager {
    lateinit var listener: (AudioFocusChange) -> Unit
    var granted = true
    var requests = 0
    var onRequest: () -> Unit = {}
    var abandons = 0
    override fun request(listener: (AudioFocusChange) -> Unit): Boolean {
        onRequest()
        requests++
        this.listener = listener
        return granted
    }
    override fun abandon() { abandons++ }
    fun signal(change: AudioFocusChange) { listener(change) }
}

private class FocusSinkFake : AudioSink {
    var level = -1f
    var written = 0
    var played = false
    override fun open() {}
    override fun setVolume(volume: Float) { this.level = volume }
    override fun mute() { setVolume(0f) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { written += length }
    override fun play() { played = true }
    override fun underruns() = 0
    override fun release() {}
}
