package fm.heterodyne.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.dp
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
    val clearNameError: () -> Unit = {},
)

fun dragTargetIndex(from: Int, distance: Float, rowHeight: Float, count: Int): Int {
    require(count > 0 && from in 0 until count && rowHeight > 0)
    return (from + distance / rowHeight).roundToInt().coerceIn(0, count - 1)
}

@Composable
fun ParkingScreen(screen: StationsScreenState, driving: Boolean, confirmed: StationEntry?,
                  actions: ParkingActions, back: () -> Unit,
                  diagnostics: @Composable (() -> Unit) -> Unit) =
    ParkingScreen(screen, driving, confirmed, actions, back, ThemeChoice.Auto, Palette.Coral, {}, {}, diagnostics = diagnostics)

@Composable
fun ParkingScreen(screen: StationsScreenState, driving: Boolean, confirmed: StationEntry?,
                  actions: ParkingActions, back: () -> Unit,
                  theme: ThemeChoice, palette: Palette,
                  selectTheme: (ThemeChoice) -> Unit, selectPalette: (Palette) -> Unit,
                  seekLimitSeconds: Int = 20, selectSeekLimit: (Int) -> Unit = {},
                  signalMethod: fm.heterodyne.app.audio.TransitionSignalMethod = fm.heterodyne.app.audio.TransitionSignalMethod.KeyClick,
                  selectSignal: (fm.heterodyne.app.audio.TransitionSignalMethod) -> Unit = {},
                  connected: Boolean = false, developerEnabled: Boolean = false,
                  developerTools: @Composable () -> Unit = {},
                  autostartEnabled: Boolean = true, selectAutostart: (Boolean) -> Unit = {},
                  scaleMarker: ScaleMarker = ScaleMarker.Pin, selectScaleMarker: (ScaleMarker) -> Unit = {},
                  parkShowLabel: Boolean = true, selectParkShowLabel: (Boolean) -> Unit = {},
                  parkLargeIcon: Boolean = false, selectParkLargeIcon: (Boolean) -> Unit = {},
                  receiverKinds: List<fm.heterodyne.app.receiver.ReceiverKind> = listOf(fm.heterodyne.app.receiver.ReceiverKind.Module),
                  receiverName: (fm.heterodyne.app.receiver.ReceiverKind) -> String = fm.heterodyne.app.receiver.ReceiverProfiles.Module::displayName,
                  selectedReceiver: fm.heterodyne.app.receiver.ReceiverKind? = fm.heterodyne.app.receiver.ReceiverKind.Module,
                  selectReceiver: (fm.heterodyne.app.receiver.ReceiverKind) -> Unit = {},
                  diagnostics: @Composable (() -> Unit) -> Unit) {
    var page by rememberSaveable { mutableStateOf("Меню стоянки") }
    LaunchedEffect(page) { actions.clearNameError() }
    LaunchedEffect(developerEnabled, selectedReceiver) {
        if ((!developerEnabled || selectedReceiver != fm.heterodyne.app.receiver.ReceiverKind.Module) && page == "Режим разработчика") page = "Диагностика"
    }
    LaunchedEffect(driving) {
        if (driving) { page = "Меню стоянки"; back() }
    }
    if (driving) return
    val goBack = { if (page == "Меню стоянки") back() else page = "Меню стоянки" }
    BackHandler { goBack() }
    val colors = LocalAppearanceColors.current
    val state = screen.state
    val catalog = screen.catalogs.firstOrNull { it.region.id == state?.selectedRegion }
    Row(Modifier.fillMaxSize().background(colors["bg"])) {
        Column(Modifier.width(DesignTokens.number("size.parkMenu.width").dp).fillMaxHeight()
            .testTag("parking-menu").background(colors["surface"])
            .padding(DesignTokens.number("space.window").dp)) {
            Control("← Эфир", true, back, Modifier.fillMaxWidth()
                .height(DesignTokens.number("size.parkMenu.back").dp), selected = true)
            Label("Режим стоянки", 16)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val destinations = listOf("Подборки", "Настройки", "Диагностика", "О приложении") +
                    if (developerEnabled && selectedReceiver == fm.heterodyne.app.receiver.ReceiverKind.Module) listOf("Режим разработчика") else emptyList()
                destinations.forEach { destination ->
                    ParkingMenuItem(destination, page == destination) { page = destination }
                }
            }
            Label(if (connected) "Приёмник: подключён" else "Приёмник: не подключён", 16)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(
            horizontal = DesignTokens.number("space.parkContent.1").dp,
            vertical = DesignTokens.number("space.parkContent.0").dp),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.section").dp)) {
            screen.error?.let { Label(it, 18) }
            if (screen.recovered) Label("Данные восстановлены: использованы значения по умолчанию", 18)
            when (page) {
                "Меню стоянки" -> Label("Режим стоянки", 24)
                "Диагностика" -> diagnostics(goBack)
                "Режим разработчика" -> if (developerEnabled) developerTools()
                "Настройки" -> {
                    var regionsOpen by remember { mutableStateOf(false) }
                    val gap = tokenDp("size.settings.gap")
                    if (regionsOpen) {
                        BackHandler { regionsOpen = false }
                        Control("← Настройки", true, { regionsOpen = false }, Modifier.fillMaxWidth(), settings = true)
                        val regions = remember(screen.catalogs) {
                            val order = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("ru"))
                            screen.catalogs.sortedWith { a, b -> order.compare(a.region.name, b.region.name) }
                        }
                        val selectedIndex = regions.indexOfFirst { it.region.id == state?.selectedRegion }.coerceAtLeast(0)
                        val listState = remember { androidx.compose.foundation.lazy.LazyListState(selectedIndex) }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("settings-regions"), state = listState) {
                            items(regions, key = { it.region.id }) { option ->
                                val selected = option.region.id == state?.selectedRegion
                                Control(option.region.name + if (selected) " ✓" else "", true, {
                                    actions.region(option.region.id)
                                    regionsOpen = false
                                }, Modifier.fillMaxWidth().height(tokenDp("size.settings.regionRow")),
                                    selected = selected, settings = true)
                            }
                        }
                    } else {
                        // Keep scroll state only for this visit; rememberScrollState uses rememberSaveable.
                        val scroll = remember { androidx.compose.foundation.ScrollState(0) }
                        Column(Modifier.width(tokenDp("size.settings.contentWidth")).weight(1f)
                            .verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(gap)) {
                            if (receiverKinds.size > 1) {
                                SettingsGroup("Приёмник", first = true)
                                SettingsOptions(receiverKinds, receiverName, { it == selectedReceiver },
                                    { if (!driving) selectReceiver(it) }, description = receiverName)
                            }
                            SettingsGroup("Запуск", first = receiverKinds.size <= 1)
                            Control("Играть последнюю Станцию при запуске", true,
                                { selectAutostart(!autostartEnabled) }, Modifier.fillMaxWidth(),
                                selected = autostartEnabled, settings = true)
                            SettingsText("Радио включится само после запуска приложения или возврата приёмника. Смена действует со следующего запуска.")
                            if (selectedReceiver == fm.heterodyne.app.receiver.ReceiverKind.Module) {
                                SettingsGroup("Поиск и Переход")
                                SettingsText("Сигнал Перехода")
                                SettingsOptions(fm.heterodyne.app.audio.TransitionSignalMethod.entries,
                                    { it.label }, { it == signalMethod }, selectSignal)
                                SettingsText("Предел Поиска")
                                val columns = DesignTokens.number("size.settings.seekLimitColumns").toInt()
                                val cellWidth = (tokenDp("size.settings.contentWidth") - gap * (columns - 1)) / columns
                                SeekPreferences.choices.chunked(columns).forEach { row ->
                                    SettingsOptions(row, { "$it с" }, { it == seekLimitSeconds }, selectSeekLimit,
                                        width = cellWidth)
                                }
                            }
                            SettingsGroup("Вид")
                            Column {
                                SettingsText("Тема")
                                SettingsOptions(ThemeChoice.entries, { it.title }, { it == theme }, selectTheme)
                            }
                            Column {
                                SettingsText("Палитра")
                                SettingsOptions(Palette.entries, { it.title }, { it == palette }, selectPalette,
                                    width = tokenDp("size.settings.paletteCard"), card = true)
                            }
                            Column {
                                SettingsText("Отметка на шкале")
                                SettingsOptions(ScaleMarker.entries, { it.title }, { it == scaleMarker }, selectScaleMarker,
                                    width = tokenDp("size.settings.markerCard"), card = true)
                            }
                            Column {
                                SettingsText("Кнопка «Стоянка»")
                                SettingsText("Подпись")
                                SettingsOptions(listOf(true, false), { if (it) "Вкл" else "Выкл" },
                                    { it == parkShowLabel }, selectParkShowLabel)
                            }
                            Column {
                                SettingsText("Размер пиктограммы")
                                SettingsOptions(listOf(false, true), { if (it) "Крупный" else "Обычный" },
                                    { it == parkLargeIcon }, selectParkLargeIcon)
                            }
                            SettingsGroup("Регион")
                            Control("Регион: ${catalog?.region?.name.orEmpty()} ›", true, { regionsOpen = true },
                                Modifier.fillMaxWidth(), settings = true)
                        }
                    }
                }
                "Подборки" -> if (state != null) {
                    val region = state.regions.getValue(state.selectedRegion)
                    var editing by rememberSaveable(state.selectedRegion) { mutableStateOf(region.activeName) }
                    var renamedTo by rememberSaveable(state.selectedRegion) { mutableStateOf<String?>(null) }
                    val selection = region.selections.firstOrNull { it.name == editing }
                        ?: region.selections.firstOrNull { it.name == renamedTo } ?: region.active
                    var name by rememberSaveable(state.selectedRegion, selection.name) { mutableStateOf(selection.name) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                        items(region.selections, key = { it.name }) { option ->
                            Control(option.name, true, { if (selection.name != option.name) actions.clearNameError(); editing = option.name; renamedTo = null }, selected = selection.name == option.name)
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                        Column(Modifier.width(DesignTokens.number("size.segment.width").dp)) {
                            BasicTextField(name, { name = it; actions.clearNameError() }, Modifier.fillMaxWidth().heightIn(min = DesignTokens.number("size.segment.height").dp)
                                .background(colors["surface2"]).padding(DesignTokens.number("space.section").dp), textStyle = designTextStyle("body", colors["text"]), singleLine = true)
                            screen.nameError?.let { Label(it, 18, colors["danger"]) }
                        }
                        Control("Создать", true, { actions.create(state.selectedRegion, name) })
                        Control("Переименовать", true, { editing = selection.name; renamedTo = name; actions.rename(state.selectedRegion, selection.name, name) })
                        Control("Удалить Подборку", region.selections.size > 1, { renamedTo = null; actions.delete(state.selectedRegion, selection.name) })
                        Control("Сделать активной", selection.name != region.activeName, { actions.activate(selection.name) })
                    }
                    Control("Сохранить текущую частоту", confirmed != null, { confirmed?.let { actions.add(state.selectedRegion, region.activeName, it) } })
                    Label("Активная Подборка: ${region.activeName}. Для порядка удерживайте Станцию и перетащите её.", 18)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.window").dp)) {
                        Column(Modifier.weight(1f)) {
                            Label("Справочник →", 22)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                                items(catalog?.stations.orEmpty(), key = { it.id }) { station ->
                                    Row(Modifier.fillMaxWidth().height(DesignTokens.number("size.editorRow.height").dp)
                                        .background(colors["surface2"]), verticalAlignment = Alignment.CenterVertically) {
                                        Label("${station.name} · ${station.frequencyKhz} кГц", 16, modifier = Modifier.weight(1f))
                                        EditorIcon("+", "Добавить ${station.name}") {
                                            actions.add(state.selectedRegion, selection.name, station.copyEntry())
                                        }
                                    }
                                }
                            }
                        }
                        var dragging by remember(selection) { mutableStateOf<Int?>(null) }
                        var distance by remember(selection) { mutableFloatStateOf(0f) }
                        val stride = with(LocalDensity.current) { DesignTokens.number("size.editorRow.stride").dp.toPx() }
                        val target = dragging?.let { dragTargetIndex(it, distance, stride, selection.entries.size) }
                        Column(Modifier.weight(1f).then(if (dragging != null)
                            Modifier.border(DesignTokens.number("line.stroke").dp, colors["accent"]) else Modifier)) {
                            Label("Подборка: ${selection.name}", 22)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                                itemsIndexed(selection.entries, key = { _, entry -> "${entry.frequencyKhz}" }) { index, entry ->
                                    val active = dragging == index
                                    Box(Modifier.fillMaxWidth().height(DesignTokens.number("size.editorRow.height").dp)
                                        .zIndex(if (active) 1f else 0f)) {
                                        if (active) Box(Modifier.matchParentSize().drawBehind {
                                            drawRoundRect(colors["edge"], style = Stroke(
                                                DesignTokens.number("line.stroke").dp.toPx(),
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DesignTokens.number("space.plateGap").dp.toPx(), DesignTokens.number("space.plateGap").dp.toPx()))))
                                        })
                                        Row(Modifier.fillMaxSize().testTag("station:${entry.frequencyKhz}")
                                            .graphicsLayer {
                                                if (active) {
                                                    translationY = distance
                                                    scaleX = DesignTokens.number("state.drag.scale")
                                                    scaleY = scaleX
                                                    rotationZ = DesignTokens.number("state.drag.rotateDeg")
                                                    shadowElevation = DesignTokens.number("shadow.drag.blur").dp.toPx()
                                                    ambientShadowColor = colors["shadow"]
                                                    spotShadowColor = colors["shadow"]
                                                }
                                            }
                                            .background(colors["surface2"], RoundedCornerShape(DesignTokens.number("radius.m").dp))
                                            .then(if (active) Modifier.border(DesignTokens.number("line.stroke").dp,
                                                colors["accent"], RoundedCornerShape(DesignTokens.number("radius.m").dp)) else Modifier)
                                            .pointerInput(selection, index) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { distance = 0f; dragging = index },
                                                    onDragCancel = { distance = 0f; dragging = null },
                                                    onDragEnd = {
                                                        val to = dragTargetIndex(index, distance, stride, selection.entries.size)
                                                        if (to != index) actions.move(state.selectedRegion, selection.name, entry, to)
                                                        distance = 0f; dragging = null
                                                    },
                                                    onDrag = { change, amount -> change.consume(); distance += amount.y },
                                                )
                                            }, verticalAlignment = Alignment.CenterVertically) {
                                            Label("${entry.name ?: "Без имени"} · ${entry.frequencyKhz} кГц", 16, modifier = Modifier.weight(1f))
                                            EditorIcon("×", "Удалить Станцию ${entry.name ?: entry.frequencyKhz}") {
                                                actions.remove(state.selectedRegion, selection.name, entry)
                                            }
                                        }
                                        if (target == index) {
                                            val after = index > (dragging ?: index)
                                            Box(Modifier.align(if (after) Alignment.BottomCenter else Alignment.TopCenter)
                                                .fillMaxWidth().height((DesignTokens.number("line.drop") * 5).dp)
                                                .background(colors["accentSoft"])) {
                                                Box(Modifier.align(if (after) Alignment.BottomCenter else Alignment.TopCenter)
                                                    .fillMaxWidth().height(DesignTokens.number("line.drop").dp)
                                                    .background(colors["accent"])
                                                    .testTag("insertion-${if (after) "after" else "before"}:$index"))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "О приложении" -> AboutScreen(catalog?.source)
            }
        }
    }
}

@Composable
private fun SettingsText(text: String) {
    androidx.compose.foundation.text.BasicText(text,
        style = designTextStyle("settingsBody", LocalAppearanceColors.current["text"]))
}

@Composable
private fun SettingsGroup(text: String, first: Boolean = false) {
    val colors = LocalAppearanceColors.current
    Column(Modifier.fillMaxWidth()) {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(colors["edge"]))
        Spacer(Modifier.height(tokenDp("size.settings.groupTop")))
        androidx.compose.foundation.text.BasicText(text, style = designTextStyle("settingsGroup", colors["text"]))
    }
}

@Composable
private fun <T> SettingsOptions(options: List<T>, title: (T) -> String, selected: (T) -> Boolean,
                                select: (T) -> Unit, width: androidx.compose.ui.unit.Dp? = null,
                                card: Boolean = false, description: ((T) -> String)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokenDp("size.settings.gap"))) {
        options.forEach { option ->
            Control(title(option), true, { select(option) },
                if (width == null) Modifier.weight(1f) else Modifier.width(width),
                selected = selected(option), description = description?.invoke(option), settings = true, settingsCard = card)
        }
    }
}
