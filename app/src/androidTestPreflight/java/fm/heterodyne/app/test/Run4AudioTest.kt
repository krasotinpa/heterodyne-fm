package fm.heterodyne.app.test

import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fm.heterodyne.app.MainActivity
import fm.heterodyne.app.audio.AudioState
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.module.TransitionState
import java.util.concurrent.atomic.AtomicReference
import fm.heterodyne.app.preflight.PreflightApplication
import fm.heterodyne.app.preflight.PreflightMediaListener
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

    private fun confirm(frequency: Int) = compose.waitUntil(15000) {
        module.transition.confirmed?.frequencyKhz == frequency && module.transition.state == TransitionState.Idle
    }

    private fun journal(): String {
        val result = AtomicReference<String>()
        app.diagnostics.readJournal { result.set(it) }
        compose.waitUntil(5000) { result.get() != null }
        return result.get()
    }

    private fun checkpoint(): String {
        val marker = "E_CHECKPOINT ${SystemClock.elapsedRealtimeNanos()}"
        module.usb.probeEvent(marker)
        compose.waitUntil(5000) { marker in journal() }
        return marker + "\n"
    }

    private fun since(marker: String): String {
        val current = journal()
        assertTrue("journal checkpoint was lost during E", marker in current)
        return current.substringAfter(marker)
    }

    private fun seededSelection(name: String) {
        startPlayback()
        val region = checkNotNull(app.session.screen.state).selectedRegion
        val stations = listOf(StationEntry(97700, "E FM 1"), StationEntry(99100, "E FM 2"))
        compose.runOnIdle {
            app.session.createSelection(region, name)
            stations.forEach { app.session.addStation(region, name, it) }
            app.session.activateSelection(name)
        }
        compose.waitUntil(15000) { app.session.activeEntries == stations }
    }

    private fun noSignal(before: String) {
        SystemClock.sleep(800)
        assertTrue("unexpected transition signal", "сигнал Перехода:" !in since(before))
    }

    private fun signal(label: String) {
        compose.onNodeWithContentDescription("Стоянка").performClick()
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("← Эфир").performClick()
    }

    private fun waitForSignal(marker: String, label: String) {
        compose.waitUntil(5000) {
            since(marker).lineSequence().any { "сигнал Перехода: $label" in it && it.endsWith(", конец") }
        }
        val events = since(marker).lineSequence().filter { "сигнал Перехода: $label" in it }.toList()
        assertEquals("signal $label", 2, events.size)
        assertTrue(events.first().endsWith(", начало"))
        assertTrue(events.last().endsWith(", конец"))
    }

    private fun assertPcmAdvancing() {
        val bytes = module.audioCounters.bytes
        compose.waitUntil(5000) { module.audioCounters.bytes > bytes }
        assertEquals(AudioState.Playing, module.audioCounters.state)
        assertEquals(1, app.syntheticPcm.starts.get())
    }

    @Test fun screenControlsAndSignals() {
        seededSelection("Экран E")
        compose.onNodeWithText("Станция ▶").performClick()
        confirm(99100)
        compose.onNodeWithText("Станция ◀").performClick()
        confirm(97700)
        for ((index, label) in listOf("Тон", "Тишина", "Звук клавиши", "Выключено").withIndex()) {
            signal(label)
            val target = if (index % 2 == 0) 99100 else 97700
            val station = if (index % 2 == 0) "2 · E FM 2" else "1 · E FM 1"
            val before = checkpoint()
            compose.onNodeWithText(station).performClick()
            confirm(target)
            if (label == "Выключено") noSignal(before)
            else {
                waitForSignal(before, label)
                if (label == "Тон") {
                    val beforeStep = checkpoint()
                    compose.onNodeWithText("Шаг ▶").performClick()
                    confirm(target + 100)
                    noSignal(beforeStep)
                    val beforeSeek = checkpoint()
                    compose.onNodeWithContentDescription("Поиск ▶").performClick()
                    confirm(target + 200)
                    val blocked = checkpoint()
                    for (arrow in listOf("▶", "◀")) {
                        compose.onNode(hasContentDescription("Поиск $arrow", substring = true))
                            .assertIsNotEnabled().performTouchInput { click() }
                    }
                    assertTrue("touch during seek cooldown sent TX", " TX " !in since(blocked))
                    noSignal(beforeSeek)
                    SystemClock.sleep(5_000)
                    val beforeReverseSeek = checkpoint()
                    compose.onNodeWithContentDescription("Поиск ◀").performClick()
                    confirm(target + 100)
                    noSignal(beforeReverseSeek)
                    note("E_SCREEN stations=next,previous step=up seek=up,down seek_cooldown=blocked step_and_seek_signal=absent")
                }
            }
            assertEquals(AudioState.Playing, module.audioCounters.state)
            assertEquals(1, app.syntheticPcm.starts.get())
            note("E_SIGNAL label=$label result=verified")
        }

        app.setReceiverConnected(false)
        compose.waitUntil(15000) { module.moduleState == ConnectionState.Disconnected }
        compose.onNodeWithTag("efir-status").assertIsDisplayed()
        val before = checkpoint()
        compose.onNodeWithText("Станция ▶").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("Шаг ▶").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("Поиск ▶").assertIsNotEnabled().performTouchInput { click() }
        SystemClock.sleep(700)
        assertTrue("disabled controls sent TX", " TX " !in since(before))
        assertEquals(null, module.transition.target)
        note("E_DISABLED station,step,seek disabled; injected touches sent no TX")
    }

    private fun mediaSession() = app.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(ComponentName(app, PreflightMediaListener::class.java))
        .singleOrNull { it.packageName == app.packageName }

    @Test fun mediaSessionAndFocus() {
        seededSelection("MediaSession E")
        compose.waitUntil(5000) { mediaSession() != null }
        val controller = checkNotNull(mediaSession()) { "foreground PlaybackService has no active MediaSession" }
        fun mediaKey(code: Int, long: Boolean = false) {
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, repeat: Int = 0, flags: Int = 0) {
                assertTrue(controller.dispatchMediaButtonEvent(
                    KeyEvent(down, SystemClock.uptimeMillis(), action, code, repeat, 0, 0, 0, flags)
                ))
            }
            if (long) {
                send(KeyEvent.ACTION_DOWN, 1, KeyEvent.FLAG_LONG_PRESS)
                send(KeyEvent.ACTION_DOWN, 2, KeyEvent.FLAG_LONG_PRESS)
            } else send(KeyEvent.ACTION_DOWN)
            send(KeyEvent.ACTION_UP)
        }

        mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        confirm(99100)
        SystemClock.sleep(1100)
        mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        confirm(97700)
        val beforeSeek = checkpoint()
        mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, long = true)
        confirm(97800)
        SystemClock.sleep(5200)
        mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, long = true)
        confirm(97700)
        val seekEvents = since(beforeSeek).lineSequence().count { it.endsWith(" R26 Seeking") }
        assertEquals("one search per long-press sequence", 2, seekEvents)
        note("E_MEDIA short=next,previous long=seek-up,seek-down seeks=$seekEvents")

        signal("Тон")
        val audio = app.getSystemService(AudioManager::class.java)
        fun focusRequest(gain: Int) = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build())
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper())).build()

        for ((gain, expected) in listOf(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK to listOf("focus DUCK", "focus volume=0.3"),
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT to listOf("focus TRANSIENT", "focus mute")
            )) {
            val request = focusRequest(gain)
            val before = checkpoint()
            val duck = gain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            try {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(request))
                compose.waitUntil(5000) { expected.all { it in since(before) } }
                assertPcmAdvancing()
                val transition = checkpoint()
                mediaKey(if (duck) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                confirm(if (duck) 99100 else 97700)
                if (duck) waitForSignal(transition, "Тон") else noSignal(transition)
            } finally {
                audio.abandonAudioFocusRequest(request)
            }
            compose.waitUntil(5000) { "focus volume=1.0" in since(before).substringAfter("focus GAIN", "") }
            assertPcmAdvancing()
            note("E_FOCUS ${expected.first()} ${expected.last()} followed by GAIN volume=1.0 without restart; " +
                "transitionTone=${if (duck) "logged" else "suppressed"}")
        }

        val permanent = focusRequest(AudioManager.AUDIOFOCUS_GAIN)
        val beforeLoss = checkpoint()
        try {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(permanent))
            compose.waitUntil(5000) {
                val events = since(beforeLoss)
                "focus volume=1.0" in events.substringAfter("focus LOSS", "")
            }
            assertPcmAdvancing()
            note("E_FOCUS focus LOSS preserved volume=1.0 and live PCM")
        } finally {
            audio.abandonAudioFocusRequest(permanent)
        }

        app.syntheticPcm.streamLost = true
        compose.waitUntil(10000) { module.audioCounters.state == AudioState.Failed }
        assertEquals(ConnectionState.Observing, module.moduleState)
        compose.waitUntil(5000) { mediaSession() == null }
        app.syntheticPcm.streamLost = false
        SystemClock.sleep(4_000)
        assertEquals(1, app.syntheticPcm.starts.get())
        assertEquals(null, mediaSession())
        note("E_LOSS EIO released MediaSession, preserved control, no PCM retry")
    }
}
