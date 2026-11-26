package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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

internal fun dragTargetIndex(from: Int, distance: Float, rowHeight: Float, count: Int): Int {
    require(count > 0 && from in 0 until count && rowHeight > 0)
    return (from + distance / rowHeight).roundToInt().coerceIn(0, count - 1)
}

@Composable
internal fun ParkingScreen(state: RadioUiState, onToolsClosed: () -> Unit = {}, send: (RadioAction) -> Unit) {
    val screen = state.stations
    val settings = state.settings
    val diagnostics = state.diagnostics
    val receiver = state.receiver
    val connected = receiver.connection == Connection.Observing
    val canEdit = Control.Edit in state.enabled
    val canSet = Control.ChangeSetting in state.enabled
    val regionId = screen.activeRegionId
    val active = screen.selections.firstOrNull { it.name == screen.activeSelectionName }
    val confirmed = state.tuning.confirmed
    fun clearIssue() { if (canEdit) send(RadioAction.Edit(SelectionEdit.ClearIssue)) }
    fun edit(change: SelectionEdit) = send(RadioAction.Edit(change))
    fun setting(change: SettingChange) = send(RadioAction.ChangeSetting(change))
    fun back() = send(RadioAction.ExitParking)
    var page by rememberSaveable { mutableStateOf("Меню стоянки") }
    fun navigate(destination: String) {
        if (page == "Подборки" && destination != page) clearIssue()
        page = destination
    }
    val toolsAvailable = diagnostics.developerEnabled
    LaunchedEffect(toolsAvailable) {
        if (!toolsAvailable && page == "Режим разработчика") page = "Диагностика"
    }
    if (state.moving) return
    val goBack = { if (page == "Меню стоянки") back() else navigate("Меню стоянки") }
    BackHandler { goBack() }
    val colors = LocalAppearanceColors.current
    Row(Modifier.fillMaxSize().background(colors["bg"])) {
        Column(Modifier.width(DesignTokens.number("size.parkMenu.width").dp).fillMaxHeight()
            .testTag("parking-menu").background(colors["surface"])
            .padding(DesignTokens.number("space.window").dp)) {
            Control("← Эфир", Control.ExitParking in state.enabled, ::back, Modifier.fillMaxWidth()
                .height(DesignTokens.number("size.parkMenu.back").dp), selected = true)
            Label("Режим стоянки", 16)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val destinations = listOf("Подборки", "Настройки", "Диагностика", "О приложении") +
                    if (toolsAvailable) listOf("Режим разработчика") else emptyList()
                destinations.forEach { destination ->
                    ParkingMenuItem(destination, page == destination) { navigate(destination) }
                }
            }
            Label(if (connected) "Приёмник: подключён" else "Приёмник: не подключён", 16)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(
            horizontal = DesignTokens.number("space.parkContent.1").dp,
            vertical = DesignTokens.number("space.parkContent.0").dp),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.section").dp)) {
            screen.dataIssue?.let { Label(it.text(), 18) }
            when (screen.editIssue) {
                EditIssue.LastSelection -> Label("Нельзя удалить последнюю Подборку", 18)
                EditIssue.NotFound -> Label("Подборка или Станция не найдены", 18)
                else -> Unit
            }
            when (page) {
                "Меню стоянки" -> Label("Режим стоянки", 24)
                "Диагностика" -> DiagnosticsScreen(state, send)
                "Режим разработчика" -> if (toolsAvailable) {
                    DisposableEffect(onToolsClosed) { onDispose(onToolsClosed) }
                    DeveloperToolsScreen(diagnostics, state.enabled, send)
                }
                "Настройки" -> {
                    var regionsOpen by remember { mutableStateOf(false) }
                    var deletingRegion by remember { mutableStateOf<RegionView?>(null) }
                    val gap = tokenDp("size.settings.gap")
                    if (regionsOpen) {
                        BackHandler { if (deletingRegion != null) deletingRegion = null else regionsOpen = false }
                        Control("← Настройки", true, { deletingRegion = null; regionsOpen = false }, Modifier.fillMaxWidth(), settings = true)
                        val regions = screen.regions
                        val selectedIndex = regions.indexOfFirst { it.id == regionId }.coerceAtLeast(0)
                        val listState = remember { androidx.compose.foundation.lazy.LazyListState(selectedIndex) }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("settings-regions"), state = listState) {
                            items(regions, key = { it.id }) { option ->
                                val selected = option.id == regionId
                                val obsolete = option.obsolete
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    Control(option.name + (if (obsolete) " · устарел" else "") + if (selected) " ✓" else "", Control.SelectRegion in state.enabled, {
                                        send(RadioAction.SelectRegion(option.id))
                                        deletingRegion = null
                                        regionsOpen = false
                                    }, Modifier.weight(1f).height(tokenDp("size.settings.regionRow")),
                                        selected = selected, settings = true)
                                    if (obsolete) Control("Удалить", Control.DeleteObsoleteRegion in state.enabled, { deletingRegion = option },
                                        Modifier.width(tokenDp("size.settings.regionRow") * 2),
                                        description = "Удалить Регион ${option.name}", settings = true)
                                }
                            }
                        }
                        deletingRegion?.let { target ->
                            Label("Удалить Регион «${target.name}» и все его Подборки?", 18)
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                Control("Отмена", true, { deletingRegion = null }, Modifier.weight(1f), settings = true)
                                Control("Удалить Регион и Подборки", Control.DeleteObsoleteRegion in state.enabled, {
                                    send(RadioAction.DeleteObsoleteRegion(target.id))
                                    deletingRegion = null
                                }, Modifier.weight(1f), settings = true)
                            }
                        }
                    } else {
                        // Keep scroll state only for this visit; rememberScrollState uses rememberSaveable.
                        val scroll = remember { androidx.compose.foundation.ScrollState(0) }
                        Column(Modifier.width(tokenDp("size.settings.contentWidth")).weight(1f)
                            .verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(gap)) {
                            if (receiver.availableProfiles.size > 1) {
                                SettingsGroup("Приёмник", first = true)
                                SettingsOptions(receiver.availableProfiles, { it.displayName }, { it.id == receiver.selected },
                                    { setting(SettingChange.Receiver(it.id)) }, enabled = canSet, description = { it.displayName })
                            }
                            SettingsGroup("Запуск", first = receiver.availableProfiles.size <= 1)
                            Control("Играть последнюю Станцию при запуске", canSet,
                                { setting(SettingChange.Autostart(!settings.autostart)) }, Modifier.fillMaxWidth(),
                                selected = settings.autostart, settings = true)
                            SettingsText("Радио включится само после запуска приложения или возврата приёмника. Смена действует со следующего запуска.")
                            if (settings.transitionSignal != null || settings.seekLimitSeconds != null) {
                                SettingsGroup("Поиск и Переход")
                                if (settings.transitionSignal != null) {
                                    SettingsText("Сигнал Перехода")
                                    SettingsOptions(SignalChoice.entries, { it.title }, { it == settings.transitionSignal },
                                        { setting(SettingChange.TransitionSignal(it)) }, enabled = canSet)
                                }
                                if (settings.seekLimitSeconds != null) {
                                    SettingsText("Предел Поиска")
                                    val columns = DesignTokens.number("size.settings.seekLimitColumns").toInt()
                                    val cellWidth = (tokenDp("size.settings.contentWidth") - gap * (columns - 1)) / columns
                                    (10..60 step 5).chunked(columns).forEach { row ->
                                        SettingsOptions(row, { "$it с" }, { it == settings.seekLimitSeconds },
                                            { setting(SettingChange.SeekLimit(it)) }, enabled = canSet, width = cellWidth)
                                    }
                                }
                            }
                            SettingsGroup("Вид")
                            SettingsRow("Тема") {
                                SettingsOptions(ThemeChoice.entries, { it.title }, { it == settings.theme }, { setting(SettingChange.Theme(it)) }, enabled = canSet)
                            }
                            SettingsRow("Палитра") {
                                SettingsOptions(Palette.entries, { it.title }, { it == settings.palette }, { setting(SettingChange.PaletteChoice(it)) }, enabled = canSet,
                                    width = tokenDp("size.settings.paletteCard"), card = true)
                            }
                            SettingsRow("Отметка на шкале") {
                                SettingsOptions(ScaleMarker.entries, { it.title }, { it == settings.scaleMarker }, { setting(SettingChange.Marker(it)) }, enabled = canSet,
                                    width = tokenDp("size.settings.markerCard"), card = true)
                            }
                            SettingsRow("Кнопка «Стоянка»") {
                                SettingsText("Подпись")
                                SettingsOptions(listOf(true, false), { if (it) "Вкл" else "Выкл" },
                                    { it == settings.parkShowLabel }, { setting(SettingChange.ParkLabel(it)) }, enabled = canSet)
                            }
                            SettingsRow("Размер пиктограммы") {
                                SettingsOptions(listOf(false, true), { if (it) "Крупный" else "Обычный" },
                                    { it == settings.parkLargeIcon }, { setting(SettingChange.ParkIcon(it)) }, enabled = canSet)
                            }
                            SettingsGroup("Справочник")
                            Control(if (screen.catalogUpdate == CatalogUpdate.InProgress) "Обновление Справочника…" else "Обновить Справочник",
                                Control.UpdateCatalog in state.enabled && screen.catalogUpdate != CatalogUpdate.InProgress, { send(RadioAction.UpdateCatalog) }, Modifier.fillMaxWidth(), settings = true)
                            when (val update = screen.catalogUpdate) {
                                CatalogUpdate.NoUpdate -> SettingsText("Нет обновлений")
                                CatalogUpdate.Failed -> SettingsText("Не удалось обновить Справочник")
                                is CatalogUpdate.Updated -> SettingsText("Справочник обновлён: Ревизия ${update.revision}")
                                else -> Unit
                            }
                            SettingsGroup("Регион")
                            Control("Регион: ${screen.regions.firstOrNull { it.id == regionId }?.name.orEmpty()} ›", true, { regionsOpen = true },
                                Modifier.fillMaxWidth(), settings = true)
                        }
                    }
                }
                "Подборки" -> if (regionId != null && active != null) {
                    var editing by rememberSaveable(regionId) { mutableStateOf(active.name) }
                    var renamedTo by rememberSaveable(regionId) { mutableStateOf<String?>(null) }
                    val selection = screen.selections.firstOrNull { it.name == editing }
                        ?: screen.selections.firstOrNull { it.name == renamedTo } ?: active
                    val ref = SelectionRef(regionId, selection.name)
                    var name by rememberSaveable(regionId, selection.name) { mutableStateOf(selection.name) }
                    var formOpen by rememberSaveable(regionId, selection.name) { mutableStateOf(false) }
                    var stationFrequency by rememberSaveable(regionId, selection.name) { mutableStateOf<Int?>(null) }
                    var stationName by rememberSaveable(regionId, selection.name) { mutableStateOf("") }
                    var frequencyText by rememberSaveable(regionId, selection.name) { mutableStateOf("") }
                    var targetSelection by rememberSaveable(regionId, selection.name) { mutableStateOf(selection.name) }
                    var localNameError by remember(regionId, selection.name) { mutableStateOf<String?>(null) }
                    var localFrequencyError by remember(regionId, selection.name) { mutableStateOf<String?>(null) }
                    val targetEntries = screen.selections.firstOrNull { it.name == targetSelection }?.entries
                    var previousTargetEntries by remember(regionId, targetSelection) {
                        mutableStateOf(targetEntries)
                    }
                    LaunchedEffect(targetEntries) {
                        if (previousTargetEntries != targetEntries) formOpen = false
                        previousTargetEntries = targetEntries
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                        items(screen.selections, key = { it.name }) { option ->
                            Control(option.name, true, { if (selection.name != option.name) clearIssue(); editing = option.name; renamedTo = null }, selected = selection.name == option.name)
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                        Column(Modifier.width(DesignTokens.number("size.segment.width").dp)) {
                            BasicTextField(name, { name = it; clearIssue() }, Modifier.fillMaxWidth().heightIn(min = DesignTokens.number("size.segment.height").dp)
                                .background(colors["surface2"]).padding(DesignTokens.number("space.section").dp), textStyle = designTextStyle("body", colors["text"]), singleLine = true)
                            if (!formOpen) screen.editIssue.nameMessage(formOpen)?.let { Label(it, 18, colors["danger"]) }
                        }
                        Control("Создать", canEdit, { formOpen = false; edit(SelectionEdit.Create(SelectionRef(regionId, name))) })
                        Control("Переименовать", canEdit, { formOpen = false; editing = selection.name; renamedTo = name; edit(SelectionEdit.Rename(ref, name)) })
                        Control("Удалить Подборку", canEdit && screen.selections.size > 1, { formOpen = false; renamedTo = null; edit(SelectionEdit.Delete(ref)) })
                        Control("Сделать активной", Control.ActivateSelection in state.enabled && selection.name != active.name, { send(RadioAction.ActivateSelection(regionId, selection.name)) })
                    }
                    fun openForm(entry: StationView?, target: String, edit: Boolean) {
                        clearIssue()
                        localNameError = null; localFrequencyError = null
                        stationFrequency = if (edit) entry?.frequencyKhz else null
                        stationName = if (edit) entry?.name.orEmpty() else ""
                        frequencyText = entry?.let { formatStationFrequency(it.frequencyKhz) }.orEmpty()
                        targetSelection = target
                        formOpen = true
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                        Control("Добавить Станцию", canEdit, { openForm(null, selection.name, false) })
                        Control("Сохранить текущую частоту", canEdit && confirmed != null, {
                            confirmed?.let { openForm(it, active.name, false) }
                        })
                    }
                    if (formOpen) {
                        BackHandler { formOpen = false; clearIssue() }
                        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                            Column(Modifier.weight(1f)) {
                                Label("Имя Станции", 18)
                                BasicTextField(stationName, {
                                    stationName = it; localNameError = null; clearIssue()
                                }, Modifier.fillMaxWidth().testTag("station-name").background(colors["surface2"])
                                    .padding(DesignTokens.number("space.section").dp),
                                    textStyle = designTextStyle("body", colors["text"]), singleLine = true)
                                (localNameError ?: screen.editIssue.nameMessage(formOpen))?.let { Label(it, 18, colors["danger"]) }
                            }
                            Column(Modifier.weight(1f)) {
                                Label("Частота, МГц", 18)
                                BasicTextField(frequencyText, {
                                    frequencyText = it; localFrequencyError = null; clearIssue()
                                }, Modifier.fillMaxWidth().testTag("station-frequency").background(colors["surface2"])
                                    .padding(DesignTokens.number("space.section").dp), enabled = stationFrequency == null,
                                    textStyle = designTextStyle("body", colors["text"]), singleLine = true)
                                (localFrequencyError ?: screen.editIssue.frequencyMessage())?.let { Label(it, 18, colors["danger"]) }
                            }
                            Control("Сохранить Станцию", canEdit, {
                                val trimmed = stationName.trim()
                                val frequency = stationFrequency ?: parseStationFrequency(frequencyText)
                                localNameError = if (trimmed.isEmpty() || trimmed.length > 32)
                                    "Введите имя Станции: от 1 до 32 символов" else null
                                localFrequencyError = if (frequency == null)
                                    "Введите FM-частоту 87,5–108,0 МГц с шагом 0,1" else null
                                if (localNameError == null && frequency != null) {
                                    if (stationFrequency != null) edit(SelectionEdit.EditName(SelectionRef(regionId, targetSelection), frequency, trimmed))
                                    else edit(SelectionEdit.Add(SelectionRef(regionId, targetSelection), StationView(frequency, trimmed)))
                                }
                            })
                            Control("Отмена", true, { formOpen = false; clearIssue() })
                        }
                    } else screen.editIssue.frequencyMessage()?.let { Label(it, 18, colors["danger"]) }
                    Label("Активная Подборка: ${active.name}. Для порядка удерживайте Станцию и перетащите её.", 18)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(DesignTokens.number("space.window").dp)) {
                        Column(Modifier.weight(1f)) {
                            Label("Справочник →", 22)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp)) {
                                items(screen.catalog, key = { it.id }) { station ->
                                    Row(Modifier.fillMaxWidth().height(DesignTokens.number("size.editorRow.height").dp)
                                        .background(colors["surface2"]), verticalAlignment = Alignment.CenterVertically) {
                                        Label("${station.name} · ${station.frequencyKhz} кГц", 16, modifier = Modifier.weight(1f))
                                        EditorIcon("+", "Добавить ${station.name}", canEdit) {
                                            edit(SelectionEdit.Add(ref, StationView(station.frequencyKhz, station.name), station.id))
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
                                            .clickable(enabled = canEdit) { openForm(entry, selection.name, true) }
                                            .pointerInput(selection, index, canEdit) {
                                                if (!canEdit) return@pointerInput
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { distance = 0f; dragging = index },
                                                    onDragCancel = { distance = 0f; dragging = null },
                                                    onDragEnd = {
                                                        val to = dragTargetIndex(index, distance, stride, selection.entries.size)
                                                        if (to != index) edit(SelectionEdit.Move(ref, entry.frequencyKhz, to))
                                                        distance = 0f; dragging = null
                                                    },
                                                    onDrag = { change, amount -> change.consume(); distance += amount.y },
                                                )
                                            }, verticalAlignment = Alignment.CenterVertically) {
                                            Label("${entry.name ?: "Без имени"} · ${entry.frequencyKhz} кГц", 16, modifier = Modifier.weight(1f))
                                            EditorIcon("×", "Удалить Станцию ${entry.name ?: entry.frequencyKhz}", canEdit) {
                                                edit(SelectionEdit.Remove(ref, entry.frequencyKhz))
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
                "О приложении" -> AboutScreen(diagnostics.buildVersion, screen.catalogSource)
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
private fun SettingsRow(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(tokenDp("size.settings.gap"))) {
        SettingsText(label)
        content()
    }
}

@Composable
private fun SettingsGroup(text: String, first: Boolean = false) {
    val colors = LocalAppearanceColors.current
    Column(Modifier.fillMaxWidth()) {
        if (!first) Box(Modifier.fillMaxWidth().height(tokenDp("line.edge")).background(colors["edge"]))
        Spacer(Modifier.height(tokenDp("size.settings.groupTop")))
        androidx.compose.foundation.text.BasicText(text, style = designTextStyle("settingsGroup", colors["text"]))
    }
}

@Composable
private fun <T> SettingsOptions(options: List<T>, title: (T) -> String, selected: (T) -> Boolean,
                                select: (T) -> Unit, width: androidx.compose.ui.unit.Dp? = null,
                                card: Boolean = false, enabled: Boolean, description: ((T) -> String)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokenDp("size.settings.gap"))) {
        options.forEach { option ->
            Control(title(option), enabled, { select(option) },
                if (width == null) Modifier.weight(1f) else Modifier.width(width),
                selected = selected(option), description = description?.invoke(option), settings = true, settingsCard = card)
        }
    }
}

private fun formatStationFrequency(frequencyKhz: Int): String {
    val fraction = if (frequencyKhz % 100 == 0) (frequencyKhz % 1000 / 100).toString()
        else (frequencyKhz % 1000).toString().padStart(3, '0')
    return "${frequencyKhz / 1000},$fraction"
}

private fun parseStationFrequency(text: String): Int? {
    val normalized = text.trim().replace(',', '.')
    if (!normalized.matches(Regex("[0-9]{2,3}(\\.[0-9]{1,3})?"))) return null
    val khz = try { normalized.toBigDecimal().movePointRight(3).intValueExact() }
        catch (_: ArithmeticException) { return null }
    return khz.takeIf { it in 87500..108000 && it % 100 == 0 }
}

private fun EditIssue?.nameMessage(station: Boolean) = when (this) {
    EditIssue.InvalidName -> if (station) "Введите имя Станции: от 1 до 32 символов" else "Введите имя Подборки"
    EditIssue.DuplicateName -> "Подборка с таким именем уже есть"
    else -> null
}

private fun EditIssue?.frequencyMessage() = when (this) {
    EditIssue.InvalidFrequency -> "Введите FM-частоту 87,5–108,0 МГц с шагом 0,1"
    EditIssue.DuplicateFrequency -> "Частота уже есть в Подборке"
    else -> null
}
