package fm.heterodyne.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fm.heterodyne.app.StationsDiagnostics

@Composable
fun DeveloperToolsScreen(app: StationsDiagnostics, parked: Boolean) {
    if (!parked || !app.developerMode.enabled) return
    var journal by remember { mutableStateOf("Загрузка…") }
    var frames by remember { mutableStateOf(emptyList<String>()) }
    var byte7 by remember { mutableStateOf<Int?>(null) }
    var counters by remember { mutableStateOf(app.audioCounters) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            app.readJournal { journal = it }
            frames = app.diagnosticFrames.snapshot()
            byte7 = app.diagnosticFrames.byte7
            counters = app.audioCounters
            kotlinx.coroutines.delay(1000)
        }
    }
    val colors = LocalAppearanceColors.current
    val style = designTextStyle("body", colors["text"])
    val monoStyle = designTextStyle("mono", colors["text"])
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
        item { Label("Режим разработчика", 24) }
        item { BasicText("Звук: байты ${counters.bytes}, ошибки ${counters.badPackets}, выравнивания ${counters.realignments}, underrun ${counters.underruns}", style = style) }
        item { BasicText("Звук: максимальная пауза в Playing ${counters.maxReadGapMs} мс, минимальный запас ${counters.minBufferedFrames} кадров", style = style) }
        item { BasicText("Журнал: потеряно ${counters.lostLogRecords}, сбоев записи ${counters.logWriteFailures}", style = style) }
        item { BasicText("Байт +7 (0x24): ${byte7 ?: "—"}", style = style) }
        items(fm.heterodyne.app.usb.DiagnosticCommand.entries) { command ->
            Control(command.label, parked && !app.exportBusy, {
                if (parked && app.developerMode.enabled) app.diagnosticCommand(command)
            }, Modifier.fillMaxWidth())
        }
        item { BasicText("Живой поток кадров (последние 100 записей USB)", style = style) }
        items(frames) { BasicText(it, style = monoStyle) }
        item { BasicText("Журнал (последние 32 КБ; экспорт содержит весь Журнал)", style = style) }
        item { BasicText(journal, style = monoStyle) }
    }
}
