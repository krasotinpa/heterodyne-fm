package fm.heterodyne.app.audio

import android.media.AudioTrack
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [StaticTransitionToneTest.StaticPcmAudioTrack::class])
@LooperMode(LooperMode.Mode.PAUSED)
class StaticTransitionToneTest {
    @Implements(AudioTrack::class)
    class StaticPcmAudioTrack : ShadowAudioTrack() {
        // Настоящий write() переводит MODE_STATIC в готовое состояние после записи PCM.
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        protected fun native_write_short(
            samples: ShortArray, offset: Int, size: Int, format: Int, blocking: Boolean,
        ): Int = size
    }

    @Test fun missingMarkerFinishesAfterOneSecond() {
        val tone = StaticTransitionTone()
        var finishes = 0
        tone.play(1f) { finishes++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals(0, finishes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, finishes)
        tone.stop()
        assertEquals(1, finishes)
    }

    @Test fun stoppingCancelsFallbackAndOldTimeoutCannotStopReplacement() {
        val tone = StaticTransitionTone()
        var first = 0
        var second = 0
        tone.play(1f) { first++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        tone.stop()
        tone.play(1f) { second++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, first)
        assertEquals(0, second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, second)
    }
}
