package fm.heterodyne.app.audio

import fm.heterodyne.app.Journal
import kotlin.math.PI
import kotlin.math.sin

enum class TransitionSignalMethod(val label: String) { Off("Выключено"), Tone("Тон"), KeyClick("Звук клавиши"), Silence("Тишина") }

// Выбор системного эффекта или PCM решается в #208.
const val KEY_CLICK_USES_SYSTEM_EFFECT = true

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

/** PCM 48 кГц: синус 1,5 кГц, 15 мс, амплитуда 0,3, края по 2 мс. */
fun transitionKeyClick(): ShortArray = ShortArray(720) { index ->
    val envelope = minOf(1.0, index / 96.0, (719 - index) / 96.0)
    (32767 * 0.3 * envelope * sin(2 * PI * 1500 * index / 48000)).toInt().toShort()
}

/** Главный поток владеет сигналом. Звуковой поток не вызывает этот класс. */
class TransitionSignal(
    private val output: TransitionToneOutput,
    private val journal: Journal,
    private val playing: () -> Boolean,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val schedule: (Long, () -> Unit) -> Unit,
    private val silence: (Boolean) -> Unit = {},
    private val keyClickOutput: TransitionToneOutput = output,
    private val systemKeyClick: (Float) -> Unit = {},
    private val keyClickUsesSystemEffect: Boolean = KEY_CLICK_USES_SYSTEM_EFFECT,
) {
    private var selected = TransitionSignalMethod.Tone
    private var focusVolume = 1f
    private var active: Any? = null
    private var activeMethod = TransitionSignalMethod.Tone
    private var activeOutput: TransitionToneOutput? = null

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
        if (selected != TransitionSignalMethod.Tone && selected != TransitionSignalMethod.KeyClick) return
        if (!playing() || focusVolume == 0f) return
        stop()
        val token = Any()
        active = token
        activeMethod = selected
        val system = selected == TransitionSignalMethod.KeyClick && keyClickUsesSystemEffect
        activeOutput = if (system) null else if (selected == TransitionSignalMethod.KeyClick) keyClickOutput else output
        event(activeMethod, "начало")
        try {
            if (system) {
                systemKeyClick(if (focusVolume == 1f) -1f else focusVolume)
                // API не сообщает конец системного эффекта; фиксируем завершение вызова.
                finish(token)
            } else {
                checkNotNull(activeOutput).play(focusVolume) { finish(token) }
            }
        } catch (error: Exception) {
            event(activeMethod, "ошибка ${error.javaClass.simpleName}")
            stop()
        }
    }

    fun setFocusVolume(value: Float) {
        focusVolume = value
        if (value == 0f) stopTone() else activeOutput?.setVolume(value)
    }

    fun stop() {
        stopSilence()
        stopTone()
    }

    private fun stopTone() {
        val token = active ?: return
        try { activeOutput?.stop() } finally { finish(token) }
    }

    private fun finish(token: Any) {
        if (active !== token) return
        active = null
        activeOutput = null
        event(activeMethod, "конец")
    }

    private fun event(method: TransitionSignalMethod, phase: String) {
        val path = if (method == TransitionSignalMethod.KeyClick) {
            if (keyClickUsesSystemEffect) " (системный)" else " (PCM)"
        } else ""
        try { journal.event("сигнал Перехода: ${method.label}$path, $phase") } catch (_: Exception) {
            // Ошибка Журнала не останавливает звук.
        }
    }
}
