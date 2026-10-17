package fm.heterodyne.app.audio

import org.junit.Assert.*
import org.junit.Test

class AudioBudgetTest {
    @Test fun prefillHoldsHalfSecondBeforePlay() {
        var playing = false
        val source = object : AudioSource {
            override fun open() {}
            override fun read(out: ByteArray): Int { out.fill(0); return out.size }
            override fun badPackets() = 0L
            override fun close() {}
        }
        val sink = object : AudioSink {
            override fun setVolume(volume: Float) {}
            override fun open() {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
            override fun play() { playing = true }
            override fun underruns() = 0
            override fun release() {}
        }
        val loop = AudioTest(source, sink)
        repeat(38) { loop.receive() }
        assertFalse("Less than 0.5 seconds must remain buffered", playing)
        repeat(2) { loop.receive() }
        assertTrue(playing)
    }
}
