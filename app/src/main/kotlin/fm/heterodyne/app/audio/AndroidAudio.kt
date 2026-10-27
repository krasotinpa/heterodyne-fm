package fm.heterodyne.app.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/** Borrows the Module connection descriptor. All native calls run on the audio thread. */
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
    @Volatile private var track: AudioTrack? = null
    @Volatile private var volume = 1f
    private var writtenFrames = 0L
    private var headWraps = 0L
    private var lastHead = 0L
    override fun open() {
        val minBuffer = AudioTrack.getMinBufferSize(AudioTest.RATE, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT)
        writtenFrames = 0
        headWraps = 0
        lastHead = 0
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(AudioTest.RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuffer, AudioTest.RATE * AudioTest.BYTES_PER_FRAME)) // 1 s
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        checkNotNull(track).setVolume(volume)
    }
    override fun setVolume(volume: Float) { this.volume = volume; track?.setVolume(volume) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var sent = 0
        while (sent < length) {
            val count = checkNotNull(track).write(bytes, offset + sent, length - sent)
            check(count > 0) { "AudioTrack write failed" }
            sent += count
            writtenFrames += count / AudioTest.BYTES_PER_FRAME
        }
    }
    override fun play() { checkNotNull(track).play() }
    override fun bufferedFrames(): Long {
        val head = checkNotNull(track).playbackHeadPosition.toLong() and 0xffffffffL
        if (head < lastHead) headWraps += 1L shl 32
        lastHead = head
        return (writtenFrames - headWraps - head).coerceAtLeast(0)
    }
    override fun underruns() = checkNotNull(track).underrunCount
    override fun release() {
        val opened = track ?: return
        track = null
        try { opened.stop() } catch (_: IllegalStateException) {
            // A failed start can leave the track uninitialized.
        } finally { opened.release() }
    }
}

class AndroidAudioFocusManager(private val manager: AudioManager) : AudioFocusManager {
    private var request: AudioFocusRequest? = null

    override fun request(listener: (AudioFocusChange) -> Unit): Boolean {
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> listener(AudioFocusChange.Loss)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener(AudioFocusChange.Duck)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> listener(AudioFocusChange.Transient)
                    AudioManager.AUDIOFOCUS_GAIN -> listener(AudioFocusChange.Gain)
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        request = next
        return manager.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        val previous = request ?: return
        request = null
        manager.abandonAudioFocusRequest(previous)
    }
}

/** Главный поток играет готовый PCM. Второй AudioTrack не запрашивает фокус. */
class StaticTransitionTone : TransitionToneOutput {
    private companion object {
        const val FALLBACK_STOP_DELAY_MILLIS = 1000L
    }

    private var track: AudioTrack? = null
    private var completed: (() -> Unit)? = null
    private var fallback: Runnable? = null
    private val main = Handler(Looper.getMainLooper())
    private val pcm = transitionTone()

    override fun play(volume: Float, finished: () -> Unit) {
        stop()
        try {
            val next = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            track = next
            completed = finished
            check(next.state != AudioTrack.STATE_UNINITIALIZED)
            check(next.write(pcm, 0, pcm.size) == pcm.size)
            next.setVolume(volume)
            next.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) { if (this@StaticTransitionTone.track === track) stop() }
                override fun onPeriodicNotification(track: AudioTrack) {}
            }, main)
            check(next.setNotificationMarkerPosition(pcm.size) == AudioTrack.SUCCESS)
            next.play()
            val timeout = Runnable { if (track === next) stop() }
            fallback = timeout
            main.postDelayed(timeout, FALLBACK_STOP_DELAY_MILLIS)
        } catch (error: Exception) { stop(); throw error }
    }

    override fun setVolume(volume: Float) { track?.setVolume(volume) }

    override fun stop() {
        fallback?.let { main.removeCallbacks(it) }
        fallback = null
        val previous = track
        val finished = completed
        track = null
        completed = null
        try {
            if (previous != null) {
                try { previous.stop() } catch (_: IllegalStateException) {
                    // Незавершённый запуск тоже требует освобождения AudioTrack.
                } finally { previous.release() }
            }
        } finally { finished?.invoke() }
    }
}
