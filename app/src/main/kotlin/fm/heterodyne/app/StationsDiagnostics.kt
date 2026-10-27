package fm.heterodyne.app

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import fm.heterodyne.app.usb.AndroidUsbAdapter

class StationsDiagnostics(
    private val context: android.content.Context,
    private val main: Handler,
    private val usb: AndroidUsbAdapter,
) {
    val audioCounters get() = usb.audioCounters
    val developerMode: DeveloperMode

    @Volatile private var probeEnabled = false
    @Volatile private var probeUiMode = context.resources.configuration.uiMode
    var environmentProbeLines by mutableStateOf(emptyList<String>())
        private set
    private val probeThread = lazy {
        android.os.HandlerThread("EnvironmentProbe").apply { start() }
    }
    private val probeHandler by lazy { Handler(probeThread.value.looper) }
    private val environmentProbe by lazy {
        fm.heterodyne.app.ui.EnvironmentProbe(
            fm.heterodyne.app.ui.AndroidEnvironmentProbe(context, probeHandler) { probeUiMode },
            android.os.SystemClock::elapsedRealtime,
        ) { if (probeEnabled) usb.probeEvent(it) }
    }
    private val probeTick = object : Runnable {
        override fun run() {
            val lines = environmentProbe.poll(probeEnabled)
            main.post { environmentProbeLines = if (probeEnabled) lines else emptyList() }
            if (probeEnabled) probeHandler.postDelayed(this, 1000)
        }
    }

    private fun enableProbe(enabled: Boolean) {
        probeEnabled = enabled
        if (!enabled && !probeThread.isInitialized()) return
        probeHandler.post {
            probeHandler.removeCallbacks(probeTick)
            if (enabled && probeEnabled) probeTick.run()
            else if (!enabled) {
                environmentProbe.poll(false)
                main.post { environmentProbeLines = emptyList() }
            }
        }
    }

    fun updateProbeUiMode(uiMode: Int) {
        probeUiMode = uiMode
        if (probeEnabled) {
            probeHandler.post {
                probeHandler.removeCallbacks(probeTick)
                if (probeEnabled) probeTick.run()
            }
        }
    }

    init {
        val preferences = context.getSharedPreferences("diagnostics", android.content.Context.MODE_PRIVATE)
        developerMode = DeveloperMode(preferences.getBoolean("developer", false)) {
            preferences.edit().putBoolean("developer", it).apply()
            enableProbe(it)
        }
        enableProbe(developerMode.enabled)
    }
    var exportBusy by mutableStateOf(false)
        private set
    var diagnosticMessage by mutableStateOf<String?>(null)
        private set
    val diagnosticFrames get() = usb.frames
    fun readJournal(done: (String) -> Unit) { usb.readJournal { text -> main.post { done(text) } } }
    fun exportJournal() {
        if (exportBusy) return
        exportBusy = true
        diagnosticMessage = null
        usb.exportJournal(MediaStoreDownloads(context.contentResolver)) { result -> main.post {
            exportBusy = false
            diagnosticMessage = result.fold(
                { "Журнал сохранён: ${it.path}, ${it.sizeBytes} байт" },
                { "Не удалось записать Журнал" },
            )
        } }
    }
    fun diagnosticCommand(command: fm.heterodyne.app.usb.DiagnosticCommand) {
        if (!developerMode.enabled || exportBusy) return
        usb.diagnosticCommand(command) { result -> main.post { diagnosticMessage = "${command.label}: $result" } }
    }

}
