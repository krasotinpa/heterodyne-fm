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

    init {
        val preferences = context.getSharedPreferences("diagnostics", android.content.Context.MODE_PRIVATE)
        developerMode = DeveloperMode(preferences.getBoolean("developer", false)) {
            preferences.edit().putBoolean("developer", it).apply()
        }
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
