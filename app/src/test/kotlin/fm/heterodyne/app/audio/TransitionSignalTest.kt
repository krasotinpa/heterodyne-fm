package fm.heterodyne.app.audio

import fm.heterodyne.app.DiagnosticLog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

class TransitionSignalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun keyClickHasSpecifiedPcm() {
        val pcm = transitionKeyClick()
        assertEquals(720, pcm.size)
        assertEquals(0, pcm.first().toInt())
        assertEquals(0, pcm.last().toInt())
        assertTrue(pcm.maxOf { abs(it.toInt()) } in 9800..9831)
        // 10 мс без краёв содержат 15 периодов 1,5 кГц.
        assertEquals(15, (120 until 600).count { pcm[it - 1] <= 0 && pcm[it] > 0 })
        assertTrue(pcm.take(24).maxOf { abs(it.toInt()) } < 2400)
        assertTrue(pcm.takeLast(24).maxOf { abs(it.toInt()) } < 2400)
    }

    @Test fun keyClickSelectsSystemOrPcmAndRequiresAudiblePlayback() {
        for (system in listOf(true, false)) {
            val directory = temporary.newFolder()
            val systemVolumes = mutableListOf<Float>()
            val pcmVolumes = mutableListOf<Float>()
            var playing = false
            var ended: () -> Unit = {}
            val click = object : TransitionToneOutput {
                override fun play(volume: Float, finished: () -> Unit) { pcmVolumes.add(volume); ended = finished }
                override fun setVolume(volume: Float) {}
                override fun stop() { ended() }
            }
            val tone = object : TransitionToneOutput {
                override fun play(volume: Float, finished: () -> Unit) { error("Unexpected tone") }
                override fun setVolume(volume: Float) {}
                override fun stop() {}
            }
            val signal = TransitionSignal(tone, DiagnosticLog(directory), { playing },
                schedule = { _, _ -> error("Unexpected timer") },
                keyClickOutput = click, systemKeyClick = { systemVolumes.add(it) },
                keyClickUsesSystemEffect = system)
            signal.begin(TransitionSignalMethod.KeyClick)
            signal.commandSent(0)
            signal.confirm()
            playing = true
            signal.setFocusVolume(0f)
            signal.confirm()
            assertTrue(systemVolumes.isEmpty())
            assertTrue(pcmVolumes.isEmpty())
            signal.setFocusVolume(0.3f)
            signal.commandSent(0)
            assertTrue(systemVolumes.isEmpty())
            assertTrue(pcmVolumes.isEmpty())
            signal.confirm()
            assertEquals(if (system) listOf(0.3f) else emptyList<Float>(), systemVolumes)
            assertEquals(if (system) emptyList<Float>() else listOf(0.3f), pcmVolumes)
            signal.stop()
            ended()
            signal.begin(TransitionSignalMethod.Off)
            signal.confirm()
            assertEquals(1, systemVolumes.size + pcmVolumes.size)
            val events = java.io.File(directory, "diagnostic.log").readText()
            val path = if (system) "системный" else "PCM"
            assertEquals(1, events.lineSequence().count { it.contains("сигнал Перехода: Звук клавиши ($path), начало") })
            assertEquals(1, events.lineSequence().count { it.contains("сигнал Перехода: Звук клавиши ($path), конец") })
        }
    }

    @Test fun systemKeyClickUsesPlatformDefaultVolumeUnlessDucked() {
        val volumes = mutableListOf<Float>()
        val output = object : TransitionToneOutput {
            override fun play(volume: Float, finished: () -> Unit) { error("Unexpected PCM") }
            override fun setVolume(volume: Float) {}
            override fun stop() {}
        }
        val signal = TransitionSignal(output, DiagnosticLog(temporary.newFolder()), { true },
            schedule = { _, _ -> error("Unexpected timer") },
            systemKeyClick = { volumes.add(it) }, keyClickUsesSystemEffect = true)
        signal.begin(TransitionSignalMethod.KeyClick)
        signal.setFocusVolume(1f)
        signal.confirm()
        signal.setFocusVolume(0.3f)
        signal.confirm()
        assertEquals(listOf(-1f, 0.3f), volumes)
    }

    @Test fun toneHasSpecifiedDurationFrequencyAmplitudeAndRamps() {
        val pcm = transitionTone()
        assertEquals(3840, pcm.size)
        assertEquals(0, pcm.first().toInt())
        assertEquals(0, pcm.last().toInt())
        assertTrue(pcm.maxOf { abs(it.toInt()) } in 9800..9831)
        // 880 Гц дают 44 полных периода за 50 мс вне плавных краёв.
        assertEquals(44, (480 until 2880).count { pcm[it - 1] <= 0 && pcm[it] > 0 })
        assertTrue(pcm.take(48).maxOf { abs(it.toInt()) } < 2000)
        assertTrue(pcm.takeLast(48).maxOf { abs(it.toInt()) } < 2000)
    }

    @Test fun failedOutputReportsErrorThroughSafeJournal() {
        val events = mutableListOf<String>()
        var stops = 0
        val output = object : TransitionToneOutput {
            override fun play(volume: Float, finished: () -> Unit) { throw IllegalStateException("failed") }
            override fun setVolume(volume: Float) {}
            override fun stop() { stops++ }
        }
        val journal = object : fm.heterodyne.app.Journal {
            override fun tx(frame: ByteArray) {}
            override fun rx(frame: ByteArray) {}
            override fun event(text: String) {
                events.add(text)
                if (text.contains("ошибка")) throw IllegalArgumentException("journal failed")
            }
        }
        val signal = TransitionSignal(output, journal, { true },
            schedule = { _, _ -> error("Unexpected tone timer") })
        signal.begin(TransitionSignalMethod.Tone)
        signal.confirm()
        assertEquals(listOf("сигнал Перехода: Тон, начало",
            "сигнал Перехода: Тон, ошибка IllegalStateException", "сигнал Перехода: Тон, конец"), events)
        assertEquals(1, stops)
    }

    @Test fun onlyAudibleTonePlaysAndSelectionIsCapturedAtTransitionStart() {
        val directory = temporary.newFolder()
        var method = TransitionSignalMethod.Tone
        var playing = false
        val volumes = mutableListOf<Float>()
        var ended: () -> Unit = {}
        val output = object : TransitionToneOutput {
            override fun play(volume: Float, finished: () -> Unit) { volumes.add(volume); ended = finished }
            override fun setVolume(volume: Float) {}
            override fun stop() {}
        }
        val signal = TransitionSignal(output, DiagnosticLog(directory), { playing },
            schedule = { _, _ -> error("Unexpected tone timer") })
        signal.begin(method); signal.confirm()
        assertTrue(volumes.isEmpty())
        playing = true
        signal.begin(method); method = TransitionSignalMethod.Off; signal.confirm(); ended()
        assertEquals(listOf(1f), volumes)
        signal.begin(method); signal.confirm()
        assertEquals(1, volumes.size)
        method = TransitionSignalMethod.Tone
        signal.setFocusVolume(0f); signal.begin(method); signal.confirm()
        assertEquals(1, volumes.size)
        signal.setFocusVolume(0.3f); signal.begin(method); signal.confirm(); ended()
        assertEquals(listOf(1f, 0.3f), volumes)
        val text = java.io.File(directory, "diagnostic.log").readText()
        assertTrue(text.contains("сигнал Перехода: Тон, начало"))
        assertTrue(text.contains("сигнал Перехода: Тон, конец"))
    }
}
