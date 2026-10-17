package fm.heterodyne.app.audio

import fm.heterodyne.app.DiagnosticLog
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioThreadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun blockingModuleCommandDoesNotInterruptAudioAndStopJoinsBeforeUsbRelease() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val playing = CountDownLatch(1)
        var lastWrite = 0L
        var underruns = 0
        val source = object : AudioSource {
            override fun open() { calls.add("open:${Thread.currentThread().name}") }
            override fun read(out: ByteArray): Int { Thread.sleep(8); out.fill(0); return out.size }
            override fun badPackets() = 0L
            override fun close() { calls.add("discard/reap:${Thread.currentThread().name}") }
        }
        val sink = object : AudioSink {
            override fun setVolume(volume: Float) {}
            override fun open() {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val now = System.nanoTime()
                if (lastWrite != 0L && now - lastWrite > 100_000_000) underruns++
                lastWrite = now
            }
            override fun play() { playing.countDown() }
            override fun underruns() = underruns
            override fun release() { calls.add("sink release") }
        }
        val port = object : UsbAudioPort {
            override fun claim() {}
            override fun alt1() {}
            override fun alt0() { calls.add("alt0:${Thread.currentThread().name}") }
            override fun release() { calls.add("release:${Thread.currentThread().name}") }
        }
        val audio = AudioPlayback({ true }, source, sink, port, DiagnosticLog(temporary.newFolder()),
            threadFactory = { task -> Thread(task, "audio-test") })
        val module = Executors.newSingleThreadExecutor { task -> Thread(task, "module-test") }
        try {
            assertTrue(module.submit<Boolean> { audio.play() }.get())
            assertTrue(playing.await(2, TimeUnit.SECONDS))
            val before = audio.snapshot.bytes
            module.submit { Thread.sleep(300) }.get()
            assertTrue(audio.snapshot.bytes > before)
            assertEquals(0, audio.snapshot.underruns)
            assertTrue(audio.snapshot.maxReadGapMs <= 100)
            module.submit { audio.stop(); calls.add("descriptor close") }.get()
            assertEquals(listOf("open:audio-test", "discard/reap:audio-test", "sink release",
                "alt0:module-test", "release:module-test", "descriptor close"), calls)
        } finally { module.submit { audio.stop() }.get(); module.shutdownNow() }
    }

    @Test fun stopTimeoutKeepsDescriptorAndInterfacesOpenAndFails() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val reaped = CountDownLatch(1)
        var released = false
        val source = object : AudioSource {
            override fun open() {}
            override fun read(out: ByteArray): Int { entered.countDown(); resume.await(); return 0 }
            override fun badPackets() = 0L
            override fun close() { reaped.countDown() }
        }
        val sink = object : AudioSink {
            override fun setVolume(volume: Float) {}
            override fun open() {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
            override fun play() {}
            override fun underruns() = 0
            override fun release() {}
        }
        val port = object : UsbAudioPort {
            override fun claim() {}
            override fun alt1() {}
            override fun alt0() { released = true }
            override fun release() { released = true }
        }
        val log = DiagnosticLog(temporary.newFolder())
        val audio = AudioPlayback({ true }, source, sink, port, log,
            threadFactory = { task -> Thread(task, "audio-test") })
        try {
            audio.play()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val start = System.nanoTime()
            assertFalse(audio.stop())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) in 1900..3000)
            assertFalse(released)
            assertEquals(AudioState.Failed, audio.snapshot.state)
            assertTrue(log.readTail(32768).contains("Audio stop timeout"))
        } finally { resume.countDown() }
        assertTrue(reaped.await(1, TimeUnit.SECONDS))
        audio.receive()
        assertFalse(audio.stop())
        assertFalse(released)
        assertEquals(AudioState.Failed, audio.snapshot.state)
    }
}
