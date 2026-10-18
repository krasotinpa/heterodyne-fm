package fm.heterodyne.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import fm.heterodyne.app.BuildConfig
import fm.heterodyne.app.StationsScreenState
import fm.heterodyne.app.stations.StationEntry

/** Storage intents only; opening the editor never tunes the Module. */
data class ParkingActions(
    val region: (String) -> Unit = {},
    val activate: (String) -> Unit = {},
    val create: (String, String) -> Unit = { _, _ -> },
    val rename: (String, String, String) -> Unit = { _, _, _ -> },
    val delete: (String, String) -> Unit = { _, _ -> },
    val add: (String, String, StationEntry) -> Unit = { _, _, _ -> },
    val remove: (String, String, StationEntry) -> Unit = { _, _, _ -> },
    val move: (String, String, StationEntry, Int) -> Unit = { _, _, _, _ -> },
)

fun dragTargetIndex(from: Int, distance: Float, rowHeight: Float, count: Int): Int {
    require(count > 0 && from in 0 until count && rowHeight > 0)
    return (from + (distance / rowHeight).toInt()).coerceIn(0, count - 1)
}

@Composable
fun ParkingScreen(screen: StationsScreenState, driving: Boolean, confirmed: StationEntry?,
                  actions: ParkingActions, back: () -> Unit,
                  diagnostics: @Composable (() -> Unit) -> Unit) =
    ParkingScreen(screen, driving, confirmed, actions, back, ThemeChoice.Auto, Palette.Coral, {}, {}, diagnostics)

@Composable
fun ParkingScreen(screen: StationsScreenState, driving: Boolean, confirmed: StationEntry?,
                  actions: ParkingActions, back: () -> Unit,
                  theme: ThemeChoice, palette: Palette,
                  selectTheme: (ThemeChoice) -> Unit, selectPalette: (Palette) -> Unit,
                  diagnostics: @Composable (() -> Unit) -> Unit) {
    var page by rememberSaveable { mutableStateOf("Меню стоянки") }
    LaunchedEffect(driving) {
        if (driving) { page = "Меню стоянки"; back() }
    }
    if (driving) return
    val goBack = { if (page == "Меню стоянки") back() else page = "Меню стоянки" }
    BackHandler { goBack() }
    if (page == "Диагностика") { diagnostics(goBack); return }
    val colors = LocalAppearanceColors.current
    val state = screen.state
    val catalog = screen.catalogs.firstOrNull { it.region.id == state?.selectedRegion }
    Column(Modifier.fillMaxSize().background(colors["bg"]).padding(DesignTokens.number("space.window").dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Control(if (page == "Меню стоянки") "Эфир" else "Назад", true, goBack)
            Label(page, 24)
        }
        screen.error?.let { Label(it, 18) }
        if (screen.recovered) Label("Данные восстановлены: использованы значения по умолчанию", 18)
        when (page) {
            "Меню стоянки" -> listOf("Подборка", "Настройки", "Диагностика", "О приложении").forEach { destination ->
                Control(destination, true, { page = destination })
            }
            "Настройки" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.section").dp)) {
                Label("Регион", 24)
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                    screen.catalogs.forEach { option ->
                        Control(option.region.name, true, { actions.region(option.region.id) },
                            selected = option.region.id == state?.selectedRegion)
                    }
                }
                Label("Тема", 24)
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                    ThemeChoice.entries.forEach { option ->
                        Control(option.title, true, { selectTheme(option) }, selected = option == theme)
                    }
                }
                Label("Палитра", 24)
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                    Palette.entries.forEach { option ->
                        Control(option.title, true, { selectPalette(option) }, selected = option == palette)
                    }
                }
            }
            "Подборка" -> if (state != null) {
                val region = state.regions.getValue(state.selectedRegion)
                var editing by rememberSaveable(state.selectedRegion) { mutableStateOf(region.activeName) }
                var renamedTo by rememberSaveable(state.selectedRegion) { mutableStateOf<String?>(null) }
                val selection = region.selections.firstOrNull { it.name == editing }
                    ?: region.selections.firstOrNull { it.name == renamedTo } ?: region.active
                var name by rememberSaveable(state.selectedRegion, selection.name) { mutableStateOf(selection.name) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(region.selections, key = { it.name }) { option ->
                        Control(option.name, true, { editing = option.name; renamedTo = null }, selected = selection.name == option.name)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(name, { name = it }, Modifier.weight(1f).heightIn(min = 56.dp)
                        .background(colors["surface2"]).padding(12.dp), textStyle = designTextStyle("body", colors["text"]), singleLine = true)
                    Control("Создать", true, { actions.create(state.selectedRegion, name) })
                    Control("Переименовать", true, { editing = selection.name; renamedTo = name; actions.rename(state.selectedRegion, selection.name, name) })
                    Control("Удалить Подборку", region.selections.size > 1, { renamedTo = null; actions.delete(state.selectedRegion, selection.name) })
                    Control("Сделать активной", selection.name != region.activeName, { actions.activate(selection.name) })
                }
                Control("Сохранить текущую частоту", confirmed != null, { confirmed?.let { actions.add(state.selectedRegion, region.activeName, it) } })
                Label("Активная Подборка: ${region.activeName}. Для порядка удерживайте Станцию и перетащите её.", 18)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Label("Справочник →", 22)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(catalog?.stations.orEmpty(), key = { it.id }) { station ->
                                Control("${station.name} · ${station.frequencyKhz} кГц →", true,
                                    { actions.add(state.selectedRegion, selection.name, station.copyEntry()) }, Modifier.fillMaxWidth())
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Label("Подборка: ${selection.name}", 22)
                        val rowHeight = with(LocalDensity.current) { 72.dp.toPx() }
                        LazyColumn {
                            itemsIndexed(selection.entries, key = { _, entry -> "${entry.band}:${entry.frequencyKhz}" }) { index, entry ->
                                var distance by remember { mutableFloatStateOf(0f) }
                                Row(Modifier.fillMaxWidth().height(72.dp).testTag("station:${entry.frequencyKhz}")
                                    .pointerInput(selection, index) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { distance = 0f },
                                            onDragCancel = { distance = 0f },
                                            onDragEnd = {
                                                val to = dragTargetIndex(index, distance, rowHeight, selection.entries.size)
                                                if (to != index) actions.move(state.selectedRegion, selection.name, entry, to)
                                                distance = 0f
                                            },
                                            onDrag = { change, amount -> change.consume(); distance += amount.y },
                                        )
                                    }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Label("${entry.name ?: "Без имени"} · ${entry.frequencyKhz} кГц", 18, modifier = Modifier.weight(1f))
                                    Control("Удалить Станцию", true, { actions.remove(state.selectedRegion, selection.name, entry) })
                                }
                            }
                        }
                    }
                }
            }
            "О приложении" -> {
                Label("Версия ${BuildConfig.VERSION_NAME}", 22)
                val uri = LocalUriHandler.current
                var error by remember { mutableStateOf(false) }
                catalog?.source?.let { source ->
                    Control("Источник: ${source.text}", true, {
                        try { uri.openUri(source.url) }
                        catch (_: Exception) { error = true }
                    })
                }
                if (error) Label("Нет приложения для открытия ссылки", 18)
            }
        }
    }
}
