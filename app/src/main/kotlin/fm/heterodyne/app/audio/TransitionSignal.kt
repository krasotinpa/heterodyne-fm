package fm.heterodyne.app.audio

import fm.heterodyne.app.Journal
import kotlin.math.PI
import kotlin.math.sin

enum class TransitionSignalMethod(val label: String) { Off("Выключено"), Tone("Тон") }

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
) {
    private var selected = TransitionSignalMethod.Tone
    private var focusVolume = 1f
    private var active: Any? = null

    fun begin(method: TransitionSignalMethod) { selected = method }

    fun confirm() {
        if (selected != TransitionSignalMethod.Tone || !playing() || focusVolume == 0f) return
        stop()
        val token = Any()
        active = token
        event("начало")
        try {
            output.play(focusVolume) { finish(token) }
        } catch (error: Exception) {
            event("ошибка ${error.javaClass.simpleName}")
            stop()
        }
    }

    fun setFocusVolume(value: Float) {
        focusVolume = value
        if (value == 0f) stop() else output.setVolume(value)
    }

    fun stop() {
        val token = active ?: return
        try { output.stop() } finally { finish(token) }
    }

    private fun finish(token: Any) {
        if (active !== token) return
        active = null
        event("конец")
    }

    private fun event(phase: String) {
        try { journal.event("сигнал Перехода: Тон, $phase") } catch (_: Exception) {
            // Ошибка Журнала не останавливает звук.
        }
    }
}
