package fm.heterodyne.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fm.heterodyne.ui.contract.*

@Composable
internal fun DeveloperToolsScreen(view: DiagnosticsView, enabled: Set<Control>, send: (RadioAction) -> Unit) {
    val refresh by rememberUpdatedState { if (Control.RefreshDiagnostics in enabled) send(RadioAction.RefreshDiagnostics) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refresh()
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    val detail = view.detail ?: return
    val counters = detail.audio
    val colors = LocalAppearanceColors.current
    val style = designTextStyle("body", colors["text"])
    val monoStyle = designTextStyle("mono", colors["text"])
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
        item { Label("Режим разработчика", 24) }
        items(detail.environmentLines) { BasicText(it, style = style) }
        item { BasicText("Звук: байты ${counters.bytes}, ошибки ${counters.badPackets}, выравнивания ${counters.realignments}, underrun ${counters.underruns}", style = style) }
        item { BasicText("Звук: максимальная пауза в Playing ${counters.maxReadGapMillis ?: "—"} мс, пауз > 100 мс: ${counters.longPauses}, минимальный запас ${counters.minBufferedFrames ?: "—"} кадров", style = style) }
        item { BasicText("Журнал: потеряно ${counters.lostLogRecords}, сбоев записи ${counters.logWriteFailures}", style = style) }
        item { BasicText("Байт +7 (0x24): ${detail.rawByte7 ?: "—"}", style = style) }
        items(DiagnosticAction.entries) { command ->
            Control(command.label(), Control.RunDiagnostic in enabled && command in detail.commands,
                { send(RadioAction.RunDiagnostic(command)) }, Modifier.fillMaxWidth())
        }
        detail.commandResult?.let { item { BasicText("${it.action.label()}: ${it.outcome}", style = style) } }
        item { BasicText("Живой поток кадров (последние 100 записей USB)", style = style) }
        items(detail.frames) { BasicText(it, style = monoStyle) }
        item { BasicText("Журнал (последние 32 КБ; экспорт содержит весь Журнал)", style = style) }
        item { BasicText(detail.journalTail.orEmpty(), style = monoStyle) }
    }
}

private fun DiagnosticAction.label() = when (this) {
    DiagnosticAction.CheckLink -> "requestAllInfo"
    DiagnosticAction.StepFrequencyUp -> "TUNEDOWN"
    DiagnosticAction.StepFrequencyDown -> "TUNEUP"
    DiagnosticAction.ReturnToFm -> "BAND — возврат на FM"
    DiagnosticAction.ReselectCurrent -> "playSelect — текущая частота"
    DiagnosticAction.SeekFrequencyUp -> "SEEKDOWN"
    DiagnosticAction.SeekFrequencyDown -> "SEEKUP"
}
