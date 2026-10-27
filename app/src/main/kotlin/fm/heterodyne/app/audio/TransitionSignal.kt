package fm.heterodyne.app.audio

import fm.heterodyne.app.Journal
import kotlin.math.PI
import kotlin.math.sin

enum class TransitionSignalMethod(val label: String) { Off("Выключено"), Tone("Тон"), Silence("Тишина") }

interface TransitionToneOutput {
    fun play(volume: Float, finished: () -> Unit)
    fun setVolume(volume: Float)
    fun stop()
}

/** PCM 48 кГц: синус 880 Гц, 80 мс, амплитуда 0,3, плавные края по 5 мс. */
fun transitionTone(): ShortArray = ShortArray(3840) { index ->
    val envelope = minOf(1.0, index / 240.0, (3839 - index) / 240.0)
    (32767 * 0.3 * envelope * sin(2 * PI * 880 * index / 48000)).toInt().toShort()
}

/** Главный поток владеет сигналом. Звуковой поток не вызывает этот класс. */
class TransitionSignal(
    private val output: TransitionToneOutput,
    private val journal: Journal,
    private val playing: () -> Boolean,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val schedule: (Long, () -> Unit) -> Unit,
    private val silence: (Boolean) -> Unit = {},
) {
    private var selected = TransitionSignalMethod.Tone
    private var focusVolume = 1f
    private var active: Any? = null

    fun begin(method: TransitionSignalMethod) { selected = method }

    private var silenceStart: Long? = null
    private var silenceCommand = 0L
    private var silenceTimer: Any? = null

    /** Called on main with the actual Module send timestamp. */
    fun commandSent(sentAt: Long) {
        if (selected != TransitionSignalMethod.Silence) {
            stopSilence()
            return
        }
        if (silenceStart == null && (!playing() || focusVolume == 0f)) return
        if (silenceStart == null) {
            silenceStart = sentAt
            silence(true)
            event(TransitionSignalMethod.Silence, "начало")
        }
        silenceCommand = sentAt
        endSilenceAt(checkNotNull(silenceStart) + 3000)
    }

    private fun endSilenceAt(deadline: Long) {
        val token = Any()
        silenceTimer = token
        schedule((deadline - clock()).coerceAtLeast(0)) {
            if (silenceTimer === token) stopSilence()
        }
    }

    private fun stopSilence() {
        if (silenceStart == null) return
        silenceStart = null
        silenceTimer = null
        silence(false)
        event(TransitionSignalMethod.Silence, "конец")
    }

    fun confirm() {
        silenceStart?.let { first ->
            endSilenceAt(minOf(first + 3000, maxOf(silenceCommand + 300, clock() + 150)))
            return
        }
        if (selected != TransitionSignalMethod.Tone || !playing() || focusVolume == 0f) return
        stop()
        val token = Any()
        active = token
        event(TransitionSignalMethod.Tone, "начало")
        try {
            output.play(focusVolume) { finish(token) }
        } catch (error: Exception) {
            event(TransitionSignalMethod.Tone, "ошибка ${error.javaClass.simpleName}")
            stop()
        }
    }

    fun setFocusVolume(value: Float) {
        focusVolume = value
        if (value == 0f) stopTone() else output.setVolume(value)
    }

    fun stop() {
        stopSilence()
        stopTone()
    }

    private fun stopTone() {
        val token = active ?: return
        try { output.stop() } finally { finish(token) }
    }

    private fun finish(token: Any) {
        if (active !== token) return
        active = null
        event(TransitionSignalMethod.Tone, "конец")
    }

    private fun event(method: TransitionSignalMethod, phase: String) {
        try { journal.event("сигнал Перехода: ${method.label}, $phase") } catch (_: Exception) {
            // Ошибка Журнала не останавливает звук.
        }
    }
}
