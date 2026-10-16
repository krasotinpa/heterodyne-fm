package fm.heterodyne.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Borrows the Module connection descriptor. All native calls run on the USB worker. */
class UsbIsoSource(private val descriptor: () -> Int) : AudioSource {
    private var owned = false
    override fun open() {
        val result = UsbIso.open(descriptor())
        owned = result != -16 // EBUSY belongs to another native session, as in G2.
        check(result >= 0) { "usbfs open failed" }
    }
    override fun read(out: ByteArray) = UsbIso.read(out)
    override fun badPackets() = UsbIso.badPackets()
    override fun close() {
        if (!owned) return
        owned = false
        UsbIso.close()
    }
}

/** AudioTrack configuration from G2, with application volume set to unity. */
class AudioTrackSink : AudioSink {
    private var track: AudioTrack? = null
    override fun open() {
        val minBuffer = AudioTrack.getMinBufferSize(AudioTest.RATE, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(AudioTest.RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuffer, AudioTest.RATE * AudioTest.BYTES_PER_FRAME)) // 1 s
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        checkNotNull(track).setVolume(1.0f)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(checkNotNull(track).write(bytes, offset, length) >= 0) { "AudioTrack write failed" }
    }
    override fun play() { checkNotNull(track).play() }
    override fun underruns() = checkNotNull(track).underrunCount
    override fun release() {
        val opened = track ?: return
        track = null
        try { opened.stop() } catch (_: IllegalStateException) {
            // A failed start can leave the track uninitialized.
        } finally { opened.release() }
    }
}
