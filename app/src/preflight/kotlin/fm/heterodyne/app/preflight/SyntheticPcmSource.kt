package fm.heterodyne.app.preflight

import fm.heterodyne.app.audio.AudioSource
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** 48 kHz, stereo s16le, 1 kHz at amplitude 2048. No microphone or USB input. */
class SyntheticPcmSource : AudioSource {
    @Volatile var streamLost = false
    val starts = java.util.concurrent.atomic.AtomicInteger()
    private var opened = false
    private var phase = 0
    private var nextRead = 0L
    override fun open() { check(!opened); opened = true; phase = 0; nextRead = System.nanoTime(); starts.incrementAndGet() }
    override fun read(out: ByteArray): Int {
        check(opened)
        if (streamLost) return -5 // Synthetic EIO: stream failed while control remains attached.
        require(out.size >= 4)
        val frames = minOf(out.size / 4, 480)
        nextRead += frames * 1_000_000_000L / 48000
        while (true) {
            val remaining = nextRead - System.nanoTime()
            if (remaining <= 0) break
            LockSupport.parkNanos(remaining)
        }
        for (i in 0 until frames) {
            val sample = (2048 * sin(2 * PI * phase / 48)).roundToInt()
            phase = (phase + 1) % 48
            for (channel in 0..1) {
                out[4 * i + 2 * channel] = sample.toByte()
                out[4 * i + 2 * channel + 1] = (sample shr 8).toByte()
            }
        }
        return frames * 4
    }
    override fun badPackets() = 0L
    override fun close() { opened = false }
}
