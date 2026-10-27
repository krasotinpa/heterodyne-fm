package fm.heterodyne.app.audio

import fm.heterodyne.app.Journal
import org.junit.Assert.*
import org.junit.Test

class TransitionSilenceTest {
    private var now = 0L
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    private val levels = mutableListOf<Boolean>()
    private val events = mutableListOf<String>()
    private var playing = true
    private var tones = 0
    private val signal = TransitionSignal(object : TransitionToneOutput {
        override fun play(volume: Float, finished: () -> Unit) { tones++; finished() }
        override fun setVolume(volume: Float) {}
        override fun stop() {}
    }, object : Journal {
        override fun tx(frame: ByteArray) {}
        override fun rx(frame: ByteArray) {}
        override fun event(text: String) { events.add(text) }
    }, { playing }, clock = { now }, schedule = { delay, task -> tasks.add(now + delay to task) },
        silence = { levels.add(it) })

    private fun advance(time: Long) {
        while (true) {
            val next = tasks.filter { it.first <= time }.minByOrNull { it.first } ?: break
            tasks.remove(next); now = next.first; next.second()
        }
        now = time
    }
    private fun start() { signal.begin(TransitionSignalMethod.Silence); signal.commandSent(now) }

    @Test fun earlyConfirmationKeepsThreeHundredMilliseconds() {
        start(); advance(100); signal.confirm(); advance(299)
        assertEquals(listOf(true), levels)
        advance(300)
        assertEquals(listOf(true, false), levels)
        assertEquals(listOf("сигнал Перехода: Тишина, начало", "сигнал Перехода: Тишина, конец"), events)
    }
    @Test fun lateConfirmationAddsOneHundredFiftyMilliseconds() {
        start(); advance(1000); signal.confirm(); advance(1149)
        assertEquals(listOf(true), levels)
        advance(1150); assertEquals(listOf(true, false), levels)
    }
    @Test fun secondTransitionInvalidatesOldEndButKeepsFirstDeadline() {
        start(); advance(2400); signal.confirm(); advance(2500); start()
        advance(2550); assertEquals(listOf(true), levels)
        advance(3000); assertEquals(listOf(true, false), levels)
        advance(6000); assertEquals(2, levels.size)
    }
    @Test fun noConfirmationEndsAtThreeSecondsAndStopEndsImmediately() {
        start(); advance(2999); assertEquals(listOf(true), levels)
        advance(3000); assertEquals(listOf(true, false), levels)
        start(); signal.stop(); advance(6000)
        assertEquals(listOf(true, false, true, false), levels)
    }
    @Test fun noPlayingOrTransientOrOffProducesNoEvent() {
        playing = false; start()
        playing = true; signal.setFocusVolume(0f); start()
        signal.setFocusVolume(1f); signal.begin(TransitionSignalMethod.Off); signal.commandSent(now)
        assertTrue(events.isEmpty()); assertTrue(levels.isEmpty())
    }
    @Test fun changingToOffEndsPreviousSilenceOnNextCommand() {
        start(); advance(100)
        signal.begin(TransitionSignalMethod.Off)
        assertEquals(listOf(true), levels)
        signal.commandSent(now)
        assertEquals(listOf(true, false), levels)
        signal.confirm(); advance(3000)
        assertEquals(2, levels.size)
    }

    @Test fun changingToTonePlaysNextConfirmationDuringPreviousSilenceWindow() {
        start(); advance(2400); signal.confirm(); advance(2500)
        signal.begin(TransitionSignalMethod.Tone); signal.commandSent(now)
        advance(2520); signal.confirm()
        assertEquals(1, tones)
        assertEquals(listOf(true, false), levels)
    }

    @Test fun focusDuringSilenceDoesNotEndItAndMethodChangeAppliesNextTime() {
        start(); signal.setFocusVolume(0f); signal.begin(TransitionSignalMethod.Tone)
        assertEquals(listOf(true), levels)
        advance(3000); assertEquals(listOf(true, false), levels)
    }
}
