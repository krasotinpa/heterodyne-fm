package fm.heterodyne.app.audio

import fm.heterodyne.app.DiagnosticLog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioPlaybackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun stopReleasesResourcesInOrderExactlyOnce() {
        val calls = mutableListOf<String>()
        val playback = AudioPlayback({ true }, FakeSource(calls), FakeSink(calls), FakePort(calls),
            DiagnosticLog(temporary.newFolder()))
        assertTrue(playback.play())
        assertEquals(AudioState.Starting, playback.snapshot.state)
        repeat(20) { playback.receive() }
        assertEquals(AudioState.Playing, playback.snapshot.state)
        calls.clear()
        playback.stop()
        playback.stop()
        assertEquals(listOf("discard/reap", "alt0", "release IF1", "sink release"), calls)
        assertEquals(AudioState.Stopped, playback.snapshot.state)
    }

    @Test fun doublePlayKeepsOneSession() {
        val calls = mutableListOf<String>()
        val playback = AudioPlayback({ true }, FakeSource(calls), FakeSink(calls), FakePort(calls),
            DiagnosticLog(temporary.newFolder()))
        assertTrue(playback.play())
        assertFalse(playback.play())
        assertEquals(1, calls.count { it == "source open" })
        playback.stop()
    }

    @Test fun cleanupFailureStillReleasesRemainingResources() {
        for (failure in listOf("discard/reap", "alt0", "release IF1", "sink release")) {
            val calls = mutableListOf<String>()
            val playback = AudioPlayback({ true }, FakeSource(calls, failure), FakeSink(calls, failure),
                FakePort(calls, failure), DiagnosticLog(temporary.newFolder()))
            playback.play()
            calls.clear()
            playback.stop()
            playback.stop()
            assertEquals(listOf("discard/reap", "alt0", "release IF1", "sink release"), calls)
            assertEquals(AudioState.Failed, playback.snapshot.state)
        }
    }

    @Test fun detachSkipsAlt0OnDeadDeviceAndReleasesEverything() {
        val calls = mutableListOf<String>()
        val playback = AudioPlayback({ true }, FakeSource(calls), FakeSink(calls), FakePort(calls, "alt0"),
            DiagnosticLog(temporary.newFolder()))
        playback.play()
        repeat(20) { playback.receive() }
        assertEquals(AudioState.Playing, playback.snapshot.state)
        calls.clear()
        playback.onModuleDetached()
        playback.stop()
        assertEquals(listOf("discard/reap", "release IF1", "sink release"), calls)
        assertEquals(AudioState.Stopped, playback.snapshot.state)
    }

    @Test fun readErrorFailsAndClosesWithoutRestart() {
        val calls = mutableListOf<String>()
        val source = FakeSource(calls)
        val playback = AudioPlayback({ true }, source, FakeSink(calls), FakePort(calls),
            DiagnosticLog(temporary.newFolder()))
        playback.play()
        repeat(20) { playback.receive() }
        source.failure = "read"
        calls.clear()
        playback.receive()
        playback.receive()
        playback.stop()
        assertEquals(AudioState.Failed, playback.snapshot.state)
        assertEquals(listOf("read", "discard/reap", "alt0", "release IF1", "sink release"), calls)
    }

    @Test fun badPacketCountsAndRealignsBeforePlaybackContinues() {
        val calls = mutableListOf<String>()
        val source = FakeSource(calls)
        val sink = FakeSink(calls)
        val playback = AudioPlayback({ true }, source, sink, FakePort(calls), DiagnosticLog(temporary.newFolder()))
        playback.play()
        repeat(20) { playback.receive() }
        val before = playback.snapshot
        assertEquals(AudioState.Playing, before.state)
        assertEquals(49920L, before.bytes)
        assertEquals(0, before.realignments)
        val written = sink.written
        source.bad = 1
        playback.receive()
        repeat(7) { playback.receive() }
        assertEquals(written, sink.written)
        playback.receive()
        assertTrue(sink.written > written)
        assertEquals(1L, playback.snapshot.badPackets)
        assertEquals(1, playback.snapshot.realignments)
        assertEquals(0L, before.badPackets)
        assertEquals(2, playback.snapshot.underruns)
        playback.stop()
        assertEquals(1, calls.count { it == "sink play" })
    }

    @Test fun partialStartFailureReleasesEveryAttemptedResource() {
        for ((failure, expected) in listOf(
            "claim IF1" to listOf("release IF1"),
            "alt1" to listOf("alt0", "release IF1"),
            "source open" to listOf("discard/reap", "alt0", "release IF1"),
            "sink open" to listOf("discard/reap", "alt0", "release IF1", "sink release"),
        )) {
            val calls = mutableListOf<String>()
            val playback = AudioPlayback({ true }, FakeSource(calls, failure), FakeSink(calls, failure),
                FakePort(calls, failure), DiagnosticLog(temporary.newFolder()))
            assertFalse(playback.play())
            assertEquals(AudioState.Failed, playback.snapshot.state)
            assertEquals(expected, calls.dropWhile { it != failure }.drop(1))
            val finished = calls.toList()
            playback.stop()
            playback.receive()
            assertEquals(finished, calls)
        }
    }

    @Test fun journalRecordsLifecycleWithoutExceptionIdentifiers() {
        val directory = temporary.newFolder()
        val calls = mutableListOf<String>()
        val playback = AudioPlayback({ true }, FakeSource(calls, "read"), FakeSink(calls), FakePort(calls),
            DiagnosticLog(directory))
        playback.play()
        playback.receive()
        val events = java.io.File(directory, "diagnostic.log").readLines().map { it.substringAfter(" EVENT ") }
        assertEquals(listOf("Audio start", "Audio error", "Audio stop"), events)
    }

    @Test fun nativeReadErrorBeforeDetachCleansUpWithoutRestart() {
        for ((errno, state) in listOf(-19 to AudioState.Stopped, -5 to AudioState.Failed)) {
            val calls = mutableListOf<String>()
            val source = FakeSource(calls)
            val playback = AudioPlayback({ true }, source, FakeSink(calls), FakePort(calls),
                DiagnosticLog(temporary.newFolder()))
            playback.play()
            repeat(20) { playback.receive() }
            source.readError = errno
            calls.clear()
            playback.receive()
            playback.onModuleDetached()
            playback.receive()
            playback.stop()
            val expected = if (errno == -19) listOf("read", "discard/reap", "release IF1", "sink release")
                else listOf("read", "discard/reap", "alt0", "release IF1", "sink release")
            assertEquals(expected, calls)
            assertEquals(state, playback.snapshot.state)
        }
    }

    @Test fun repeatedEmptyReadsLeaveCountersUnchangedAndAllowStop() {
        for (readsBeforeIdle in listOf(0, 20)) {
            val calls = mutableListOf<String>()
            val source = FakeSource(calls)
            val sink = FakeSink(calls)
            val playback = AudioPlayback({ true }, source, sink, FakePort(calls),
                DiagnosticLog(temporary.newFolder()))
            playback.play()
            repeat(readsBeforeIdle) { playback.receive() }
            val before = playback.snapshot
            val writes = sink.writeCalls
            source.empty = true
            source.bad = 1
            repeat(20) { playback.receive() }
            assertEquals(before, playback.snapshot)
            assertEquals(writes, sink.writeCalls)
            calls.clear()
            playback.stop()
            playback.stop()
            assertEquals(listOf("discard/reap", "alt0", "release IF1", "sink release"), calls)
            assertEquals(AudioState.Stopped, playback.snapshot.state)
        }
    }

    @Test fun absentModuleRefusesPlaybackWithoutResources() {
        val calls = mutableListOf<String>()
        val playback = AudioPlayback({ false }, FakeSource(calls), FakeSink(calls), FakePort(calls),
            DiagnosticLog(temporary.newFolder()))
        assertFalse(playback.play())
        assertEquals(AudioState.Stopped, playback.snapshot.state)
        assertTrue(calls.isEmpty())
    }
}

private class FakeSource(private val calls: MutableList<String>, var failure: String? = null) : AudioSource {
    var bad = 0L
    var readError = 0
    var empty = false
    override fun open() { calls.add("source open"); check(failure != "source open") }
    override fun read(out: ByteArray): Int { calls.add("read"); check(failure != "read"); if (readError < 0) return readError; if (empty) return 0; out.fill(0); return out.size }
    override fun badPackets() = bad
    override fun close() { calls.add("discard/reap"); check(failure != "discard/reap") }
}

private class FakeSink(private val calls: MutableList<String>, private val failure: String? = null) : AudioSink {
    override fun setVolume(volume: Float) {}
    override fun open() { calls.add("sink open"); check(failure != "sink open") }
    var written = 0
    var writeCalls = 0
    override fun write(bytes: ByteArray, offset: Int, length: Int) { writeCalls++; written += length }
    override fun play() { calls.add("sink play") }
    override fun underruns() = 2
    override fun release() { calls.add("sink release"); check(failure != "sink release") }
}

private class FakePort(private val calls: MutableList<String>, private val failure: String? = null) : UsbAudioPort {
    override fun claim() { calls.add("claim IF1"); check(failure != "claim IF1") }
    override fun alt1() { calls.add("alt1"); check(failure != "alt1") }
    override fun alt0() { calls.add("alt0"); check(failure != "alt0") }
    override fun release() { calls.add("release IF1"); check(failure != "release IF1") }
}
