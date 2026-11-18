package fm.heterodyne.app.test

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fm.heterodyne.app.MainActivity
import fm.heterodyne.app.audio.AudioState
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.preflight.PreflightApplication
import fm.heterodyne.app.stations.StationEntry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Run4LifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PreflightApplication
    private val module get() = app.module
    private val pcm get() = app.syntheticPcm
    private val transport get() = app.fakeTransport
    private fun waitFor(test: () -> Boolean) = compose.waitUntil(15000, test)
    private fun note(text: String) = InstrumentationRegistry.getInstrumentation()
        .sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
    private fun ready() = waitFor {
        app.session.screen.state != null && module.moduleState == ConnectionState.Observing &&
            module.transition.confirmed != null
    }

    private fun playing(frequency: Int) = waitFor {
        module.audioCounters.state == AudioState.Playing &&
            module.audioCounters.bytes >= 96000 && module.transition.confirmed?.frequencyKhz == frequency
    }

    private fun unchangedStarts(ms: Long = 1_000) {
        val starts = pcm.starts.get()
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) {
            assertEquals(starts, pcm.starts.get())
            SystemClock.sleep(25)
        }
    }

    private fun lastModuleReturn(): String? {
        val journal = AtomicReference<String>()
        app.diagnostics.readJournal { journal.set(it) }
        waitFor { journal.get() != null }
        return journal.get().lineSequence().lastOrNull { "autostart reason=module-return result=" in it }
    }

    private fun assertReturnDecision(previous: String?, expected: String) {
        val decision = lastModuleReturn()
        assertNotEquals("Return must emit a fresh decision", previous, decision)
        assertTrue("Expected $expected for this return, got $decision",
            decision?.endsWith("result=$expected") == true)
    }

    @Test fun seedPlayback() {
        ready()
        compose.runOnIdle { module.pickStation(StationEntry(97700)) }
        playing(97700)
        val saved = AtomicReference<Int>()
        app.session.readLaunchStation { saved.set(it?.frequencyKhz ?: 0) }
        waitFor { saved.get() != null }
        assertEquals(97700, saved.get())
        note("F_SEED confirmed=97700 PCM=playing lastPlayed=true")
    }

    @Test fun seedRebootPlayback() {
        ready()
        val regionId = "yaroslavl"
        val name = "Проверка G"
        val station = StationEntry(97700, "Тест G")
        compose.runOnIdle { module.selectRegion(regionId) }
        waitFor { app.session.screen.state?.selectedRegion == regionId }
        compose.runOnIdle {
            app.session.createSelection(regionId, name)
            app.session.addStation(regionId, name, station)
            app.session.activateSelection(name)
        }
        waitFor { app.session.activeEntries == listOf(station) }
        seedPlayback()
    }

    private fun detach() {
        app.setReceiverConnected(false)
        waitFor {
            module.moduleState == ConnectionState.Disconnected &&
                module.audioCounters.state in listOf(AudioState.Stopped, AudioState.Failed)
        }
        assertEquals(null, module.transition.target)
    }

    @Test fun replug() {
        ready()
        compose.runOnIdle { module.pickStation(StationEntry(97700)) }
        playing(97700)
        var lastReturn = 0L
        for (cycle in 1..10) {
            if (cycle == 9) {
                compose.runOnIdle { module.userStop() }
                waitFor { module.audioCounters.state == AudioState.Stopped }
            }
            // Preserve the production 30-second module-return cooldown between playing returns.
            if (cycle in 2..8) SystemClock.sleep((lastReturn + 31_000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            val previous = pcm.starts.get()
            val previousDecision = lastModuleReturn()
            detach()
            assertEquals(AudioState.Stopped, module.audioCounters.state)
            app.setReceiverConnected(true)
            if (cycle <= 8) {
                playing(97700)
                lastReturn = SystemClock.elapsedRealtime()
                assertEquals(previous + 1, pcm.starts.get())
                unchangedStarts()
            } else {
                ready()
                unchangedStarts(2_000)
                assertEquals(previous, pcm.starts.get())
                assertEquals(AudioState.Stopped, module.audioCounters.state)
            }
            val decision = if (cycle <= 8) "selected" else "skipped"
            assertReturnDecision(previousDecision, decision)
            note("F_REPLUG cycle=$cycle confirmed=${module.transition.confirmed?.frequencyKhz} " +
                "audio=${module.audioCounters.state} restarts=${pcm.starts.get() - previous} decision=$decision")
        }
        // A quick second return while eligible must not bypass the cooldown.
        compose.runOnIdle { module.pickStation(StationEntry(97700)) }
        playing(97700)
        val before = pcm.starts.get()
        val previousDecision = lastModuleReturn()
        detach()
        app.setReceiverConnected(true)
        ready()
        unchangedStarts(2_000)
        assertEquals(before, pcm.starts.get())
        assertEquals(AudioState.Stopped, module.audioCounters.state)
        assertReturnDecision(previousDecision, "rate-limited")
        compose.runOnIdle { module.userStop() }
        note("F_COOLDOWN decision=rate-limited restarts=0")
    }

    @Test fun faults() {
        ready()
        transport.holdSelection()
        compose.runOnIdle { module.selectStation(StationEntry(97700)) }
        waitFor { module.transition.state == TransitionState.Waiting }
        assertEquals(90300, module.transition.confirmed?.frequencyKhz)
        transport.deliver(transport.takeHeldReply())
        waitFor { module.transition.confirmed?.frequencyKhz == 97700 && module.transition.target == null }
        assertEquals(AudioState.Stopped, module.audioCounters.state)
        note("F_DELAY pending before ACK and confirmed after delayed ACK")

        transport.holdSelection(splitAt = 20)
        compose.runOnIdle { module.selectStation(StationEntry(99100)) }
        waitFor { module.transition.state == TransitionState.Waiting && module.transition.target?.frequencyKhz == 99100 }
        val staleTail = transport.takeHeldReply()
        detach()
        app.setReceiverConnected(true)
        ready()
        transport.deliver(staleTail)
        unchangedStarts(2_000)
        assertEquals(90300, module.transition.confirmed?.frequencyKhz)
        assertEquals(null, module.transition.target)
        assertEquals(AudioState.Stopped, module.audioCounters.state)
        note("F_STALE pending cleared, old reply tail rejected after reconnect")

        compose.runOnIdle { module.pickStation(StationEntry(97700)) }
        playing(97700)
        pcm.streamLost = true
        waitFor { module.audioCounters.state == AudioState.Failed }
        assertEquals(ConnectionState.Observing, module.moduleState)
        val before = pcm.starts.get()
        pcm.streamLost = false
        unchangedStarts(4_000)
        assertEquals(before, pcm.starts.get())
        assertEquals(AudioState.Failed, module.audioCounters.state)
        compose.runOnIdle { module.userStop() }
        note("F_PCM_LOSS EIO observed; control stayed attached; no retry after restoration")
    }
}
