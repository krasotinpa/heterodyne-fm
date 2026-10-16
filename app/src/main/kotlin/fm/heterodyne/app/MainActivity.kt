package fm.heterodyne.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stations = application as StationsApplication
        setContent {
            var diagnostics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
            ) { uri -> stations.finishExport(uri) }
            // #84/#86 подключат реальное состояние Режима стоянки.
            val parked = true
            if (diagnostics && parked) {
                DiagnosticsScreen(stations, parked, export = {
                    stations.beginExport {
                        try { exportLauncher.launch("heterodyne-diagnostic.log") }
                        catch (_: Exception) { stations.finishExport(null) }
                    }
                }) { diagnostics = false }
                return@setContent
            }
            val snapshot = stations.screen
            val current = snapshot.state
            val catalogs = snapshot.catalogs
            var linkError by remember { mutableStateOf<String?>(null) }
            val uriHandler = LocalUriHandler.current
            val style = TextStyle(color = Color.White)
            Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                BasicText(
                    if (stations.moduleState == fm.heterodyne.app.module.ConnectionState.Observing) "Модуль подключён" else "Модуль отключён",
                    Modifier.clickable { stations.retryModulePermission() }.padding(12.dp), style,
                )
                val playing = stations.audioState == fm.heterodyne.app.audio.AudioState.Starting ||
                    stations.audioState == fm.heterodyne.app.audio.AudioState.Playing
                BasicText(
                    if (playing) "Стоп" else "Играть",
                    Modifier.clickable(enabled = stations.moduleState == fm.heterodyne.app.module.ConnectionState.Observing) {
                        if (playing) stations.stopAudio() else stations.startAudio()
                    }.padding(12.dp), style,
                )
                BasicText("Диагностика", Modifier.clickable(enabled = parked) { diagnostics = true }.padding(12.dp), style)
                BasicText("Станции", style = style)
                if (snapshot.recovered) {
                    BasicText("Данные восстановлены: использованы значения по умолчанию", style = style)
                }
                (linkError ?: snapshot.error)?.let { BasicText(it, style = style) }
                if (current == null) {
                    BasicText("Загрузка…", style = style)
                } else {
                    val catalog = catalogs.first { it.region.id == current.selectedRegion }
                    LazyColumn {
                        items(catalogs, key = { it.region.id }) { option ->
                            BasicText(
                                (if (option.region.id == current.selectedRegion) "● " else "○ ") + option.region.name,
                                modifier = Modifier.clickable { stations.selectRegion(option.region.id) }.padding(12.dp),
                                style = style,
                            )
                        }
                        item { BasicText("Наборы", style = style) }
                        items(current.regions.getValue(current.selectedRegion).sets, key = { it.name }) { set ->
                            val active = current.regions.getValue(current.selectedRegion).activeName == set.name
                            BasicText(set.name + if (active) " — Активный" else "", Modifier.padding(12.dp), style)
                        }
                        item {
                            BasicText("Источник: ${catalog.source.text}",
                                Modifier.clickable {
                                    try { uriHandler.openUri(catalog.source.url) }
                                    catch (_: android.content.ActivityNotFoundException) { linkError = "Нет приложения для открытия ссылки" }
                                }.padding(12.dp), style)
                        }
                        item { BasicText("Справочник", style = style) }
                        items(catalog.stations, key = { it.id }) { station ->
                            BasicText("${station.name} · ${station.band} · ${station.frequencyKhz} кГц", Modifier.padding(12.dp), style)
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DiagnosticsScreen(app: StationsApplication, parked: Boolean, export: () -> Unit, back: () -> Unit) {
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
    val style = TextStyle(color = Color.White)
    LazyColumn(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
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
            item { BasicText("Байт +7 (0x24): ${byte7 ?: "—"}", style = style) }
            items(fm.heterodyne.app.usb.DiagnosticCommand.entries) { command ->
                BasicText(command.label, Modifier.clickable(enabled = !app.exportBusy) {
                    app.diagnosticCommand(command)
                }.padding(12.dp), style)
            }
            item { BasicText("Живой поток кадров (последние 100 записей USB)", style = style) }
            items(frames) { BasicText(it, style = style) }
        }
        item { BasicText("Журнал (последние 32 КБ; экспорт содержит весь Журнал)", style = style) }
        item { BasicText(journal, style = style) }
    }
}
