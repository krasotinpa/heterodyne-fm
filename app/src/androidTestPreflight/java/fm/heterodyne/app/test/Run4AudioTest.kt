package fm.heterodyne.app.test

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fm.heterodyne.app.MainActivity
import fm.heterodyne.app.audio.AudioState
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.preflight.PreflightApplication
import fm.heterodyne.app.stations.StationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Run4AudioTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as PreflightApplication
    private val module get() = app.module

    private fun note(text: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "$text\n")
    })

    private fun startPlayback() {
        compose.waitUntil(15000) {
            app.session.screen.state != null && module.moduleState == ConnectionState.Observing &&
                module.transition.confirmed != null
        }
        compose.runOnIdle { module.pickStation(StationEntry(97700)) }
        compose.waitUntil(15000) {
            module.audioCounters.state == AudioState.Playing &&
                module.audioCounters.bytes >= 96000 && module.transition.confirmed?.frequencyKhz == 97700
        }
    }

    @Test fun continuousPcm() {
        startPlayback()
        val starts = app.syntheticPcm.starts.get()
        assertEquals(1, starts)
        val start = SystemClock.elapsedRealtime()
        var previousBytes = 0L
        var previousSample = start
        for (second in 0..1800) {
            val next = start + second * 1000L
            SystemClock.sleep((next - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            val now = SystemClock.elapsedRealtime()
            val counters = module.audioCounters
            assertEquals("Playback at second $second", AudioState.Playing, counters.state)
            assertEquals("PCM source starts", starts, app.syntheticPcm.starts.get())
            assertTrue("PCM byte counter moved backwards", counters.bytes >= previousBytes)
            if (second % 10 == 0) {
                assertTrue("PCM stopped progressing for at least ${now - previousSample} ms",
                    counters.bytes > previousBytes)
                previousBytes = counters.bytes
                previousSample = now
            }
            if (second % 600 == 0) {
                note("D_SAMPLE second=$second elapsed=${now - start} bytes=${counters.bytes} " +
                    "badPackets=${counters.badPackets} realignments=${counters.realignments} " +
                    "underruns=${counters.underruns} maxGapMs=${counters.maxReadGapMs} " +
                    "minFrames=${counters.minBufferedFrames} pauses=${counters.longPauses} " +
                    "lostLogs=${counters.lostLogRecords} logFailures=${counters.logWriteFailures}")
            }
        }
        note("D_CONTINUOUS state=Playing starts=$starts frequency=97700")
    }
}
