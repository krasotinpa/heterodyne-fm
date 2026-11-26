package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

private val Background: Color @Composable get() = LocalAppearanceColors.current["bg"]
private val Panel: Color @Composable get() = LocalAppearanceColors.current["surface2"]
private val Accent: Color @Composable get() = LocalAppearanceColors.current["accentText"]
private val Muted: Color @Composable get() = LocalAppearanceColors.current["muted"]

internal fun frequencyText(khz: Int) = "${khz / 1000},${khz % 1000 / 100}"

@Composable
internal fun EfirScreen(state: RadioUiState, send: (RadioAction) -> Unit) {
    val screen = state.stations
    val region = screen.activeRegionId
    val selection = screen.activeSelectionName
    val entries = screen.selections.firstOrNull { it.name == selection }?.entries.orEmpty()
    val transition = state.tuning
    val confirmed = transition.confirmed
    val displayed = confirmed ?: transition.saved
    val connected = state.receiver.connection == Connection.Observing
    val playing = state.playback.status == PlaybackStatus.Starting || state.playback.status == PlaybackStatus.Playing
    val playAction = if (playing) RadioAction.Stop else RadioAction.Start
    val canPlay = (if (playing) Control.Stop else Control.Start) in state.enabled
    val canSelect = region != null && Control.SelectStation in state.enabled
    val reason = if (connected && state.playback.focus == FocusEffect.AutostartDenied) "Нет фокуса, нажмите Старт"
        else state.disabledReason?.text()
    val saved = confirmed == null && displayed != null
    val faded = saved || transition.phase in listOf(TuningPhase.Unconfirmed, TuningPhase.Unresponsive, TuningPhase.Disconnected)
    fun selectCatalog(station: CatalogStationView) {
        if (region != null) send(RadioAction.SelectStation(region, StationRef.Catalog(station.id)))
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Background)) {
        val windowWidth = maxWidth.value
        val compact = maxHeight.value < DesignTokens.number("breakpoint.compactHeight")
        val gap = tokenDp("space.section")
        val plateHeight = tokenDp(if (compact) "size.plate.heightCompact" else "size.plate.height")
        val showActions = transition.phase == TuningPhase.Unconfirmed || !connected ||
            screen.dataIssue != null || !screen.loaded
        Column(Modifier.fillMaxSize().padding(tokenDp("space.window")),
            verticalArrangement = Arrangement.spacedBy(if (compact && showActions) tokenDp("space.plateGap") else gap)) {
            Row(Modifier.fillMaxWidth().height(tokenDp("size.topBar.height")).testTag("efir-topbar"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tokenDp(if (compact) "space.plateGap" else "space.window"))) {
                BasicText(displayed?.frequencyKhz?.let(::frequencyText) ?: "—",
                    Modifier.testTag("efir-frequency").widthIn(min = tokenDp("size.plate.presetWidth")), style = designTextStyle("freq", LocalAppearanceColors.current[if (faded) "disabledText" else "text"]))
                Column(Modifier.weight(1f)) {
                  // The RDS row is measured first (fixed), so the name and status share what is left.
                  Column(Modifier.weight(1f).clipToBounds()) {
                    BasicText(displayed?.name?.takeIf { it.isNotBlank() } ?: "Частота вне Подборки",
                        Modifier.testTag("efir-name"),
                        style = designTextStyle("title", LocalAppearanceColors.current[if (saved) "disabledText" else "text"]), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = reason ?: transition.wheelSeekHintSeconds?.takeIf { transition.phase != TuningPhase.Seeking }
                        ?.let { "Поиск доступен через $it с" } ?: when (transition.phase) {
                        TuningPhase.Waiting -> "ожидание"
                        TuningPhase.Seeking -> "идёт поиск…"
                        TuningPhase.SeekIncomplete -> "Поиск не завершён"
                        else -> screen.regions.firstOrNull { it.id == region }?.name ?: "—"
                    }
                    BasicText(status, Modifier.testTag("efir-status"),
                        style = designTextStyle(if (reason != null) "plate" else "body", when {
                            state.disabledReason == DisabledReason.EmptySelection && state.playback.focus != FocusEffect.AutostartDenied -> Accent
                            reason != null -> LocalAppearanceColors.current["danger"]
                            transition.phase == TuningPhase.Waiting || transition.phase == TuningPhase.Seeking -> Accent
                            else -> Muted
                        }), maxLines = 1)
                  }
                  Spacer(Modifier.height(tokenDp("size.rds.height")).testTag("rds-reserved"))
                }
                // Reserve the same width in every state. Hidden buttons must also reject taps.
                Box(Modifier.width(tokenDp("size.seekGroup.width")).height(tokenDp("size.seekGroup.height"))
                    .testTag("seek-group")) {
                    if (Feature.Seek in state.receiver.features) {
                        Row(Modifier.fillMaxSize()
                            .alpha(if (state.moving) 0f else 1f)
                            .then(if (state.moving) Modifier.clearAndSetSemantics {} else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(tokenDp("space.plateGap"))) {
                            SeekControl(Direction.Down, transition, connected, Control.Seek in state.enabled && !state.moving, { send(RadioAction.Seek(Direction.Down)) },
                                Modifier.size(tokenDp("size.seekButton.width"), tokenDp("size.seekButton.height")))
                            BasicText("Поиск",
                                Modifier.width(tokenDp("size.seekGroup.width") - tokenDp("size.seekButton.width") * 2 -
                                    tokenDp("space.plateGap") * 2), style = designTextStyle("label", if (connected && transition.phase == TuningPhase.Seeking) LocalAppearanceColors.current["accentText"] else Muted).copy(textAlign = TextAlign.Center,
                                    fontWeight = if (connected && transition.phase == TuningPhase.Seeking) FontWeight.Bold else FontWeight.Normal), maxLines = 1)
                            SeekControl(Direction.Up, transition, connected, Control.Seek in state.enabled && !state.moving, { send(RadioAction.Seek(Direction.Up)) },
                                Modifier.size(tokenDp("size.seekButton.width"), tokenDp("size.seekButton.height")))
                        }
                    }
                }
                Control("Стоянка", Control.EnterParking in state.enabled && !state.moving, { send(RadioAction.EnterParking) }, Modifier.size(138.dp, tokenDp("size.parkButton.height")) /* width: components.md §3, not in tokens.json */, radius = "l", parkIcon = true,
                    parkShowLabel = state.settings.parkShowLabel, parkLargeIcon = state.settings.parkLargeIcon, description = "Стоянка")
            }
            Row(Modifier.weight(1f).testTag("efir-body"), horizontalArrangement = Arrangement.spacedBy(gap)) {
                EdgeControls(Direction.Down, state.enabled, send, Modifier.fillMaxHeight(), playing, canPlay)
                Column(Modifier.weight(1f).fillMaxHeight().testTag("efir-center"),
                    verticalArrangement = Arrangement.spacedBy(if (compact && showActions) tokenDp("space.plateGap") else gap)) {
                    Box(Modifier.fillMaxWidth().height(tokenDp(if (compact) "size.spectrum.heightCompact" else "size.spectrum.height"))
                        .testTag("spectrum-reserved"))
                    BandScale(screen.catalog, entries, transition,
                        loupeEnabled = canSelect && transition.phase in listOf(TuningPhase.Idle, TuningPhase.SeekIncomplete)
                            && transition.targetKhz == null && screen.dataIssue !in listOf(DataIssue.LoadFailed, DataIssue.SaveFailed),
                        windowWidth = windowWidth, select = ::selectCatalog, playing = playing,
                        play = { if (canPlay) send(playAction) }, marker = state.settings.scaleMarker)
                    Row(Modifier.fillMaxWidth().height(plateHeight), horizontalArrangement = Arrangement.spacedBy(tokenDp("space.plateGap"))) {
                        var choosing by remember(region, selection) { mutableStateOf(false) }
                        Box {
                            Control("Подборка: ${selection ?: "—"}", Control.ActivateSelection in state.enabled, { choosing = !choosing },
                                Modifier.width(tokenDp("size.setSwitcher.width")).fillMaxHeight())
                            if (choosing && Control.ActivateSelection in state.enabled) androidx.compose.ui.window.Popup(onDismissRequest = { choosing = false }) {
                                Column(Modifier.background(Panel).verticalScroll(rememberScrollState())) {
                                    screen.selections.forEach { selection ->
                                        Control("Подборка: ${selection.name}", true, {
                                            choosing = false
                                            if (region != null) send(RadioAction.ActivateSelection(region, selection.name))
                                        }, selected = selection.name == screen.activeSelectionName)
                                    }
                                }
                            }
                        }
                        if (entries.isEmpty()) Column(Modifier.weight(1f).fillMaxHeight()
                            .border(tokenDp("line.edge"), LocalAppearanceColors.current["edge"], RoundedCornerShape(tokenDp("radius.m")))
                            .padding(horizontal = tokenDp("space.section")), verticalArrangement = Arrangement.Center) {
                            BasicText("Подборка пуста — добавьте станции в режиме стоянки", style = designTextStyle("plate", Muted))
                        } else StationRibbon(entries, transition, canSelect && selection != null, { station ->
                            if (region != null && selection != null) send(RadioAction.SelectStation(region, StationRef.Selection(selection, station.frequencyKhz)))
                        }, Modifier.weight(1f), plateHeight,
                            "$region:$selection")
                    }
                    BasicText("Справочник", style = designTextStyle("button", Muted))
                    val visible = screen.catalog
                    if (visible.isEmpty()) BasicText("Нет Станций", style = designTextStyle("body", Muted))
                    else LazyVerticalGrid(columns = GridCells.Fixed(DesignTokens.number("size.plate.catalogColumns").toInt()),
                        modifier = Modifier.weight(1f).testTag("catalog-grid"),
                        horizontalArrangement = Arrangement.spacedBy(tokenDp("space.plateGap")),
                        verticalArrangement = Arrangement.spacedBy(tokenDp("space.plateGap"))) {
                        items(visible, key = { it.id }) { entry ->
                            StationPlate(entry.name, entry.frequencyKhz, null, transition, canSelect, { selectCatalog(entry) }, Modifier.height(plateHeight))
                        }
                    }
                }
                EdgeControls(Direction.Up, state.enabled, send, Modifier.fillMaxHeight(), playing, canPlay)
            }
            if (showActions) {
                // Keep messages beside the actions so the compact catalog retains a full plate row.
                Row(Modifier.fillMaxWidth().padding(horizontal = tokenDp("size.sideColumn.width") + tokenDp("space.section")),
                    horizontalArrangement = Arrangement.spacedBy(tokenDp("space.section")),
                    verticalAlignment = Alignment.CenterVertically) {
                    val buttonModifier = Modifier.height(tokenDp("size.touch.min"))
                        .widthIn(min = tokenDp("size.touch.min"))
                    if (transition.phase == TuningPhase.Unconfirmed)
                        Control("Проверить связь", Control.CheckConnection in state.enabled, { send(RadioAction.CheckConnection) }, buttonModifier)
                    if (!connected) Control("Подключить приёмник", Control.RetryPermission in state.enabled, { send(RadioAction.RetryPermission) }, buttonModifier)
                    val messages = buildList {
                        screen.dataIssue?.let { add(it.text()) }
                        if (!screen.loaded) add("Загрузка…")
                    }
                    if (messages.isNotEmpty()) BasicText(messages.joinToString(" · "), Modifier.weight(1f),
                        style = designTextStyle("cardTitle", Accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

internal fun tokenDp(path: String) = DesignTokens.number(path).dp
@Composable
private fun EdgeControls(direction: Direction, enabled: Set<Control>, send: (RadioAction) -> Unit,
                         modifier: Modifier, playing: Boolean, canPlay: Boolean) {
    val arrow = direction.arrow()
    Column(modifier.width(tokenDp("size.sideColumn.width")), verticalArrangement = Arrangement.spacedBy(tokenDp("space.section"))) {
        if (direction == Direction.Down) Control(if (playing) "Стоп" else "Старт", canPlay, { send(if (playing) RadioAction.Stop else RadioAction.Start) },
            Modifier.fillMaxWidth().height(tokenDp("size.startStop.height")), selected = playing, radius = "xl", outlined = !playing, playback = playing)
        else Spacer(Modifier.fillMaxWidth().height(tokenDp("size.bandButton.height")).testTag("band-reserved"))
        Control("Станция $arrow", Control.MoveInSelection in enabled, { send(RadioAction.MoveInSelection(direction)) },
            Modifier.fillMaxWidth().weight(1f).heightIn(min = tokenDp("size.stationButton.minHeight")), radius = "xl", edge = true)
        Control("Шаг $arrow", Control.Step in enabled,
            { send(RadioAction.Step(direction)) }, Modifier.fillMaxWidth().height(tokenDp("size.stepButton.height")), radius = "xl", edge = true)
    }
}

@Composable
private fun StationRibbon(entries: List<StationView>, transition: TuningView,
                          connected: Boolean, select: (StationView) -> Unit, modifier: Modifier,
                          height: androidx.compose.ui.unit.Dp, selectionKey: String) {
    val list = rememberLazyListState()
    val target = transition.targetKhz ?: transition.confirmed?.frequencyKhz
    val density = LocalDensity.current
    val background = Background
    LaunchedEffect(selectionKey, target, entries) {
        val index = entries.indexOfFirst { it.frequencyKhz == target }
        if (index >= 0) {
            val itemWidth = with(density) { tokenDp("size.plate.presetWidth").roundToPx() }
            val viewport = snapshotFlow { list.layoutInfo.viewportSize.width }.first { it > 0 }
            val stride = itemWidth + with(density) { tokenDp("space.plateGap").roundToPx() }
            val destination = (index * stride - (viewport - itemWidth) / 2).coerceAtLeast(0)
            val current = list.firstVisibleItemIndex * stride + list.firstVisibleItemScrollOffset
            list.animateScrollBy((destination - current).toFloat(), tween(DesignTokens.number("motion.ribbonMs").toInt()))
        }
    }
    LazyRow(modifier.drawWithContent {
        drawContent()
        val fade = tokenDp("space.ribbonFade").toPx()
        if (list.canScrollBackward) drawRect(Brush.horizontalGradient(listOf(background, background.copy(alpha = 0f)), 0f, fade))
        if (list.canScrollForward) drawRect(Brush.horizontalGradient(listOf(background.copy(alpha = 0f), background), size.width - fade, size.width))
    }, state = list, horizontalArrangement = Arrangement.spacedBy(tokenDp("space.plateGap"))) {
        itemsIndexed(entries, key = { _, it -> "${it.frequencyKhz}" }) { index, entry ->
            StationPlate(entry.name, entry.frequencyKhz, index + 1, transition, connected, { select(entry) },
                Modifier.width(tokenDp("size.plate.presetWidth")).height(height))
        }
    }
}

@Composable
private fun StationPlate(name: String?, frequencyKhz: Int, number: Int?, transition: TuningView, connected: Boolean,
                         select: () -> Unit, modifier: Modifier) {
    val waiting = (frequencyKhz == transition.targetKhz) && transition.phase == TuningPhase.Waiting
    val current = frequencyKhz == transition.confirmed?.frequencyKhz
    Control("${number?.let { "$it · " } ?: ""}${name?.takeIf { it.isNotBlank() } ?: "Без имени"}\n" +
        frequencyText(frequencyKhz) +
        if (waiting) " · ожидание" else "", connected, select,
        modifier, selected = current || (frequencyKhz == transition.targetKhz), waiting = waiting, plate = true,
        faded = current && transition.phase in listOf(TuningPhase.Unconfirmed, TuningPhase.Unresponsive, TuningPhase.Disconnected))
}

@Composable
internal fun Control(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
                     selected: Boolean = false, description: String? = null, radius: String = "m",
                     waiting: Boolean = false, plate: Boolean = false, outlined: Boolean = false,
                     parkIcon: Boolean = false,
                     parkShowLabel: Boolean = true, parkLargeIcon: Boolean = false,
                     edge: Boolean = false, playback: Boolean? = null, faded: Boolean = false,
                     settings: Boolean = false, settingsCard: Boolean = false) {
    val colors = LocalAppearanceColors.current
    val tokens = controlColorTokens(enabled && !faded, selected)
    val shape = RoundedCornerShape(tokenDp("radius.$radius"))
    val interactions = remember { MutableInteractionSource() }
    val interactionPressed by interactions.collectIsPressedAsState()
    var touching by remember { mutableStateOf(false) }
    val pressed = enabled && (touching || interactionPressed)
    val foreground = colors[if (waiting && enabled) "accentText" else tokens.foreground]
    val overlay = remember {
        val values = DesignTokens.values.getValue("state.pressed.overlay").removePrefix("rgba(").removeSuffix(")").split(',').map { it.toFloat() }
        Color(values[0] / 255f, values[1] / 255f, values[2] / 255f, values[3])
    }
    Box(modifier.defaultMinSize(minWidth = tokenDp("size.touch.min"), minHeight = tokenDp(if (settings) "size.settings.target" else "size.touch.min"))
        .graphicsLayer {
            if (pressed) {
                scaleX = DesignTokens.number("state.pressed.scale")
                scaleY = scaleX
                translationY = tokenDp("state.pressed.offsetY").toPx()
            }
        }
        .clip(shape)
        .then(if (outlined) Modifier.border(tokenDp("line.stroke"), colors[if (enabled) "edge" else "disabledText"], shape) else Modifier)
        .background(colors[controlBackgroundToken(enabled, selected, waiting, outlined, faded)], shape)
        .drawWithContent {
            drawContent()
            if (waiting && enabled) {
                val stroke = tokenDp("line.stroke").toPx()
                drawRoundRect(colors["accentText"], topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(tokenDp("radius.$radius").toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(tokenDp("space.plateGap").toPx(), tokenDp("space.plateGap").toPx()))))
            }
            if (pressed) {
                drawRect(overlay)
                val ring = tokenDp("state.pressed.ring").toPx()
                drawRoundRect(foreground, topLeft = androidx.compose.ui.geometry.Offset(ring / 2, ring / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - ring, size.height - ring),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(tokenDp("radius.$radius").toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(ring))
            }
        }
        .semantics {
            this.selected = selected
            if (description != null) contentDescription = description
            if (edge || playback != null) this.text = AnnotatedString(text)
        }
        .pointerInput(enabled) {
            if (enabled) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touching = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val within = event.changes.all { it.position.x in 0f..size.width.toFloat() && it.position.y in 0f..size.height.toFloat() }
                        val dragged = event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }
                        if (!within || dragged || event.changes.any { it.isConsumed }) touching = false
                    } while (event.changes.any { it.pressed })
                } finally { touching = false }
            }
        }
        .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = if (parkIcon) 0.dp else tokenDp("space.plateGap")), contentAlignment = Alignment.Center) {
        if (edge || playback != null) Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
            val icon = if (playback != null) { if (playback) "■" else "▶" } else if (text.contains("◀")) "◀" else "▶"
            val iconSize = if (playback != null) "size.icon.l" else if (text.startsWith("Станция")) "size.icon.xl" else "font.style.band.size"
            BasicText(icon, style = designTextStyle("band", foreground).copy(fontSize = DesignTokens.number(iconSize).sp,
                lineHeight = DesignTokens.number(iconSize).sp))
            BasicText(if (edge) text.substringBefore(' ') else text, style = designTextStyle("button", foreground).let {
                if (text.startsWith("Шаг")) it.copy(fontSize = DesignTokens.number("font.style.body.size").sp) else it
            })
        } else if (parkIcon) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(tokenDp(
                if (parkLargeIcon) "size.parkButton.iconBoxLarge" else "size.parkButton.iconBox"))
                .testTag("park-icon")) {
                val unit = size.minDimension / 36f
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(2f * unit)
                drawRoundRect(foreground, topLeft = androidx.compose.ui.geometry.Offset(unit, unit),
                    size = androidx.compose.ui.geometry.Size(size.width - 2 * unit, size.height - 2 * unit),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * unit), style = stroke)
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(14f * unit, 26f * unit)
                    lineTo(14f * unit, 10f * unit)
                    lineTo(20f * unit, 10f * unit)
                    cubicTo(28f * unit, 10f * unit, 28f * unit, 20f * unit, 20f * unit, 20f * unit)
                    lineTo(14f * unit, 20f * unit)
                }
                drawPath(p, foreground, style = androidx.compose.ui.graphics.drawscope.Stroke(3f * unit))
            }
            if (parkShowLabel) BasicText(text, Modifier.weight(1f),
                style = designTextStyle("button", foreground).copy(textAlign = TextAlign.Center),
                maxLines = 1, softWrap = false)
        } else if (plate) Column {
            BasicText(text.substringBefore('\n'), style = designTextStyle("plate", foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(text.substringAfter('\n'), style = designTextStyle("label", if (selected || !enabled || faded) foreground else colors["muted"]), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else BasicText(text, modifier = if (settings) Modifier.fillMaxWidth() else Modifier,
            style = designTextStyle(if (settings) "settingsBody" else "button", foreground).let {
                if (settings) it.copy(textAlign = TextAlign.Center) else it
            },
            maxLines = if (settings) { if (settingsCard) 2 else 1 } else 3,
            softWrap = !settings || settingsCard, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Label(text: String, size: Int, color: Color = LocalAppearanceColors.current["text"], modifier: Modifier = Modifier) {
    val token = when (size) {
        24 -> "heading"
        22 -> "title"
        18 -> "cardTitle"
        else -> "body"
    }
    BasicText(text, modifier, designTextStyle(token, color), maxLines = 3, overflow = TextOverflow.Ellipsis)
}
