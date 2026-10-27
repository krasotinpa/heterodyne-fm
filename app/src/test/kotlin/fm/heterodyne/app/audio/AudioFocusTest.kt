package fm.heterodyne.app.audio

import fm.heterodyne.app.Journal
import fm.heterodyne.app.DiagnosticLog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioFocusTest {
    @get:Rule val temporary = TemporaryFolder()
    private val manager = FocusManagerFake()
    private val sink = FocusSinkFake()
    private val events = mutableListOf<String>()
    private val journal = object : Journal {
        override fun tx(frame: ByteArray) {}
        override fun rx(frame: ByteArray) {}
        override fun event(text: String) { events.add(text) }
    }
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
    }, DiagnosticLog(temporary.newFolder()), focus = AudioFocusController(manager, sink, journal))

    @Test fun signalSharesFocusVolumeWithoutAnotherRequest() {
        val volumes = mutableListOf<Float>()
        val focus = AudioFocusController(manager, sink, journal, signalVolume = { volumes.add(it) })
        focus.start()
        manager.signal(AudioFocusChange.Duck)
        manager.signal(AudioFocusChange.Transient)
        manager.signal(AudioFocusChange.Loss)
        manager.signal(AudioFocusChange.Gain)
        assertEquals(listOf(1f, 0.3f, 0f, 1f, 1f), volumes)
        assertEquals(1, manager.requests)
    }

    @Test fun transitionSignalFollowsFocusAndStopsWithPlayback() {
        val volumes = mutableListOf<Float>()
        val focus = AudioFocusController(manager, sink, journal, signalVolume = { volumes.add(it) })
        focus.start()
        for (change in listOf(AudioFocusChange.Duck, AudioFocusChange.Transient,
            AudioFocusChange.Gain, AudioFocusChange.Transient, AudioFocusChange.Loss)) manager.signal(change)
        focus.stop()
        assertEquals(listOf(1f, 0.3f, 0f, 1f, 0f, 1f, 0f), volumes)
    }

    @Test fun autostartChecksGrantAndPlaybackReusesTheGrantedRequest() {
        val focus = AudioFocusController(manager, sink, journal)
        manager.granted = false
        assertFalse(focus.acquireForAutostart()!!.granted)
        focus.stop()
        manager.granted = true
        assertTrue(focus.acquireForAutostart()!!.granted)
        focus.start()
        assertEquals(2, manager.requests)
    }

    @Test fun autostartDoesNotBorrowOrReleasePlaybackFocus() {
        val focus = AudioFocusController(manager, sink, journal)
        focus.start()
        assertTrue(focus.playbackActive)
        assertNull(focus.acquireForAutostart())
        assertEquals(1, manager.requests)
        assertEquals(0, manager.abandons)
    }

    @Test fun releasingOldAutostartLeaseCannotStopPlaybackOrItsNewFocus() {
        val focus = AudioFocusController(manager, sink, journal)
        val lease = focus.acquireForAutostart()!!
        focus.start() // Playback takes ownership of this session.
        lease.release()
        assertEquals(0, manager.abandons)
        focus.reclaim()
        lease.release()
        assertTrue(focus.playbackActive)
        assertEquals(1, manager.abandons)
        assertEquals(2, manager.requests)
    }

    @Test fun leaseReleasesOnlyItsOwnSessionOnce() {
        val focus = AudioFocusController(manager, sink, journal)
        val lease = focus.acquireForAutostart()!!
        assertFalse(focus.playbackActive)
        lease.release(); lease.release()
        assertEquals(1, manager.abandons)
        assertFalse(focus.playbackActive)
    }

    @Test fun journalRecordsFocusSignalsAndActionsInOrder() {
        val focus = AudioFocusController(manager, sink, journal)
        focus.start()
        manager.signal(AudioFocusChange.Duck)
        manager.signal(AudioFocusChange.Transient)
        manager.signal(AudioFocusChange.Gain)
        manager.signal(AudioFocusChange.Loss)
        focus.stop()
        assertEquals(listOf(
            "focus volume=1.0", "focus request", "focus request granted",
            "focus DUCK", "focus volume=0.3",
            "focus TRANSIENT", "focus mute",
            "focus GAIN", "focus volume=1.0",
            "focus LOSS", "focus volume=1.0", "focus abandon",
        ), events)
    }

    @Test fun journalRecordsDeniedRequestAndReclaimButSkipsStaleSignals() {
        val focus = AudioFocusController(manager, sink, journal)
        focus.reclaim()
        focus.stop()
        assertTrue(events.isEmpty())
        manager.granted = false
        focus.start()
        assertEquals(listOf("focus volume=1.0", "focus request", "focus request denied"), events)
        val old = manager.listener
        events.clear()
        manager.granted = true
        focus.reclaim()
        assertEquals(listOf("focus reclaim", "focus abandon", "focus volume=1.0",
            "focus request", "focus request granted"), events)
        events.clear()
        // Stale signals are deliberately omitted from the journal and cannot change sound.
        old(AudioFocusChange.Transient)
        assertTrue(events.isEmpty())
        assertEquals(1f, sink.level)
        focus.stop()
        events.clear()
        manager.signal(AudioFocusChange.Duck)
        focus.stop()
        assertTrue(events.isEmpty())
    }

    @Test fun reclaimRequestsFocusAgainOnlyWhileStarted() {
        val focus = AudioFocusController(manager, sink, journal)
        focus.reclaim()
        assertEquals(0, manager.requests)
        focus.start()
        val old = manager.listener
        focus.reclaim()
        assertEquals(2, manager.requests)
        old(AudioFocusChange.Transient)
        assertEquals(1f, sink.level)
        manager.signal(AudioFocusChange.Duck)
        assertEquals(0.3f, sink.level)
        focus.stop()
        focus.reclaim()
        assertEquals(2, manager.requests)
    }

    @Test fun permanentLossKeepsSoundAndStream() {
        val audio = playback()
        assertTrue(audio.play())
        manager.signal(AudioFocusChange.Loss)
        repeat(40) { audio.receive() }
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
        repeat(40) { audio.receive() }
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
        repeat(40) { audio.receive() }
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
        repeat(40) { audio.receive() }
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
