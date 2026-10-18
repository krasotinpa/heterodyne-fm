package fm.heterodyne.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stations = application as StationsApplication
        setContent {
            val appearance = remember {
                fm.heterodyne.app.ui.AppearancePreferences(getSharedPreferences("appearance", MODE_PRIVATE))
            }
            fm.heterodyne.app.ui.HeterodyneTheme(appearance.theme, appearance.palette) {
                val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
                ) { uri -> stations.diagnostics.finishExport(uri) }
                var parked by rememberSaveable { mutableStateOf(false) }
                val driving = stations.module.driving
                androidx.compose.runtime.LaunchedEffect(driving) { if (driving) parked = false }
                if (!parked || driving) {
                    fm.heterodyne.app.ui.EfirScreen(
                        stations.session.screen, stations.module.transition,
                        connected = stations.module.moduleState == fm.heterodyne.app.module.ConnectionState.Observing,
                        playing = stations.module.audioState in listOf(fm.heterodyne.app.audio.AudioState.Starting, fm.heterodyne.app.audio.AudioState.Playing),
                        driving = driving,
                        actions = fm.heterodyne.app.ui.EfirActions(
                            select = stations.module::pickStation, station = stations.module::nextStation,
                            step = stations.module::step, seek = stations.module::seek, band = stations.module::changeBand,
                            activate = stations.module::activateSelection, parked = { if (!stations.module.driving) parked = true },
                            check = stations.module::checkConnection,
                            play = {
                                if (stations.module.audioState in listOf(fm.heterodyne.app.audio.AudioState.Starting, fm.heterodyne.app.audio.AudioState.Playing))
                                    stations.module.stopAudio() else stations.module.startAudio()
                            },
                            permission = stations.module::retryModulePermission,
                        ),
                    )
                    return@HeterodyneTheme
                }
                fm.heterodyne.app.ui.ParkingScreen(
                    stations.session.screen, driving,
                    stations.module.currentStation?.takeIf {
                        stations.module.moduleState == fm.heterodyne.app.module.ConnectionState.Observing &&
                            fm.heterodyne.app.module.isSelectableFrequency(
                                fm.heterodyne.app.module.Band.valueOf(it.band.name), it.frequencyKhz)
                    },
                    fm.heterodyne.app.ui.ParkingActions(
                        region = stations.module::selectRegion, activate = stations.module::activateSelection,
                        create = stations.session::createSelection, rename = stations.session::renameSelection,
                        delete = stations.session::deleteSelection, add = stations.session::addStation,
                        remove = stations.session::removeStation, move = stations.session::moveStation,
                    ), back = { parked = false },
                    theme = appearance.theme, palette = appearance.palette,
                    selectTheme = appearance::selectTheme, selectPalette = appearance::selectPalette,
                ) { back ->
                    DiagnosticsScreen(stations.diagnostics, parked && !driving, export = {
                        stations.diagnostics.beginExport {
                            try { exportLauncher.launch("heterodyne-diagnostic.log") }
                            catch (_: Exception) { stations.diagnostics.finishExport(null) }
                        }
                    }, back = back)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DiagnosticsScreen(app: StationsDiagnostics, parked: Boolean, export: () -> Unit, back: () -> Unit) {
    var enabled by remember { mutableStateOf(app.developerMode.enabled) }
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
    val colors = fm.heterodyne.app.ui.LocalAppearanceColors.current
    val style = fm.heterodyne.app.ui.designTextStyle("body", colors["text"])
    val monoStyle = fm.heterodyne.app.ui.designTextStyle("mono", colors["text"])
    LazyColumn(Modifier.fillMaxSize().background(colors["bg"]).padding(16.dp)) {
        item { BasicText("Назад", Modifier.clickable { back() }.padding(12.dp), style) }
        item { BasicText("Диагностика · Режим стоянки", style = style) }
        item {
            BasicText("Версия ${BuildConfig.VERSION_NAME}", Modifier.clickable {
                app.developerMode.tap(parked)
                enabled = app.developerMode.enabled
            }.padding(12.dp), style)
        }
        item { BasicText("Для экспорта отключите Модуль и подключите SSD к общему USB-разъёму. После экспорта верните Модуль.", style = style) }
        item {
            BasicText(if (app.exportBusy) "Экспорт… Модуль отключён" else "Экспорт Журнала на SSD",
                Modifier.clickable(enabled = parked && !app.exportBusy) {
                    export()
                }.padding(12.dp), style)
        }
        item { app.diagnosticMessage?.let { BasicText(it, style = style) } }
        if (enabled && parked) {
            item { BasicText("Выключить Режим разработчика", Modifier.clickable {
                app.developerMode.disable(); enabled = false
            }.padding(12.dp), style) }
            item { BasicText("Звук: байты ${counters.bytes}, ошибки ${counters.badPackets}, выравнивания ${counters.realignments}, underrun ${counters.underruns}", style = style) }
            item { BasicText("Звук: максимальная пауза ${counters.maxReadGapMs} мс, минимальный запас ${counters.minBufferedFrames} кадров", style = style) }
            item { BasicText("Журнал: потеряно ${counters.lostLogRecords}, сбоев записи ${counters.logWriteFailures}", style = style) }
            item { BasicText("Байт +7 (0x24): ${byte7 ?: "—"}", style = style) }
            items(fm.heterodyne.app.usb.DiagnosticCommand.entries) { command ->
                BasicText(command.label, Modifier.clickable(enabled = !app.exportBusy) {
                    app.diagnosticCommand(command)
                }.padding(12.dp), style)
            }
            item { BasicText("Живой поток кадров (последние 100 записей USB)", style = style) }
            items(frames) { BasicText(it, style = monoStyle) }
        }
        item { BasicText("Журнал (последние 32 КБ; экспорт содержит весь Журнал)", style = style) }
        item { BasicText(journal, style = monoStyle) }
    }
}
