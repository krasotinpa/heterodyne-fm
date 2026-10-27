package fm.heterodyne.app.ui

sealed interface ProbeReading<out T> {
    data class Value<T>(val value: T) : ProbeReading<T>
    data class Unavailable(val reason: String) : ProbeReading<Nothing> {
        init { require(reason.isNotBlank()) }
    }
}

private fun <T> ProbeReading<T>.display(format: (T) -> String): String = when (this) {
    is ProbeReading.Value -> format(value)
    is ProbeReading.Unavailable -> reason
}

/** Lux and speeds are rounded so sensor noise does not write the Journal on every tick. */
data class ProbeValues(
    val uiMode: Int,
    val modeManager: ProbeReading<Pair<Int, Int>>,
    val light: ProbeReading<Float>,
    val automotive: Boolean,
    val carSpeed: ProbeReading<Float>,
    val gnssSpeed: ProbeReading<Float>,
) {
    fun lines(): List<String> = listOf(
        "1. Ночной бит uiMode: " + when (uiMode and 0x30) {
            0x20 -> "ночь (0x20)"
            0x10 -> "день (0x10)"
            else -> "не определён (0x${(uiMode and 0x30).toString(16)})"
        },
        "2. UiModeManager: " + modeManager.display { "nightMode=${it.first}, currentModeType=${it.second}" },
        "3. Датчик освещённости: " + light.display { "есть, ${"%.0f".format(java.util.Locale.ROOT, it)} лк" },
        "4. Automotive: " + if (automotive) "да" else "нет",
        "5. CarPropertyManager / PERF_VEHICLE_SPEED: " + carSpeed.display { "${"%.1f".format(java.util.Locale.ROOT, it)} м/с" },
        "6. Скорость GNSS (GPS): " + gnssSpeed.display { "${"%.1f".format(java.util.Locale.ROOT, it)} м/с" },
    )
}

/** Android services and their subscriptions stay behind this boundary. */
interface EnvironmentProbeSource {
    fun start()
    fun read(): ProbeValues
    fun stop()
}

/** Called by the probe worker, never by the audio thread. Clock is monotonic milliseconds. */
class EnvironmentProbe(
    private val source: EnvironmentProbeSource,
    private val clock: () -> Long,
    private val event: (String) -> Unit,
) {
    private var started = false
    private var previous: List<String>? = null
    private var lastWrite = 0L

    fun poll(enabled: Boolean): List<String> {
        if (!enabled) {
            if (started) source.stop()
            started = false
            previous = null
            return emptyList()
        }
        if (!started) { source.start(); started = true }
        val lines = source.read().lines()
        val now = clock()
        if (lines != previous || now - lastWrite >= 30_000) {
            event("probe: " + lines.joinToString("; "))
            lastWrite = now
        }
        previous = lines
        return lines
    }
}

/** Persist before launching so denial and Activity recreation never cause another prompt. */
class ProbePermission(
    private var requested: Boolean,
    private val saveRequested: () -> Unit,
    private val request: () -> Unit,
) {
    fun update(enabled: Boolean, granted: Boolean) {
        if (!enabled || granted || requested) return
        requested = true
        saveRequested()
        request()
    }
}
