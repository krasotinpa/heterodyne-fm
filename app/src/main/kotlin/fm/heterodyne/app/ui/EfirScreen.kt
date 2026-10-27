package fm.heterodyne.app.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fm.heterodyne.app.StationsScreenState
import fm.heterodyne.app.module.Direction
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.StationEntry

/** UI intents only. Command timing and confirmation belong to ModuleController. */
data class EfirActions(
    val select: (StationEntry) -> Unit = {},
    val station: (Direction) -> Unit = {},
    val step: (Direction) -> Unit = {},
    val seek: (Direction) -> Unit = {},
    val band: () -> Unit = {},
    val activate: (String) -> Unit = {},
    val parked: () -> Unit = {},
    val check: () -> Unit = {},
    val play: () -> Unit = {},
    val permission: () -> Unit = {},
)

private val Background: Color @Composable get() = LocalAppearanceColors.current["bg"]
private val Panel: Color @Composable get() = LocalAppearanceColors.current["surface2"]
private val Accent: Color @Composable get() = LocalAppearanceColors.current["accentText"]
private val Muted: Color @Composable get() = LocalAppearanceColors.current["muted"]

fun frequencyText(target: TuneTarget): String = if (target.band == fm.heterodyne.app.module.Band.FM)
    "${target.frequencyKhz / 1000},${target.frequencyKhz % 1000 / 100}" else "${target.frequencyKhz}"

private fun StationEntry.matches(target: TuneTarget?) = target != null &&
    band.name == target.band.name && frequencyKhz == target.frequencyKhz

@Composable
fun EfirScreen(
    screen: StationsScreenState,
    transition: TransitionSnapshot,
    connected: Boolean,
    actions: EfirActions,
    playing: Boolean = false,
    driving: Boolean = false,
    noFocus: Boolean = false,
    connectionState: fm.heterodyne.app.module.ConnectionState = if (connected)
        fm.heterodyne.app.module.ConnectionState.Observing else fm.heterodyne.app.module.ConnectionState.Disconnected,
) {
    val state = screen.state
    val catalog = screen.catalogs.firstOrNull { it.region.id == state?.selectedRegion }
    val selections = state?.regions?.get(state.selectedRegion)
    val entries = selections?.active?.entries.orEmpty()
    val reason = if (noFocus && connected) DisabledReason.NoFocus
        else disabledReason(connectionState, transition.state, transition.confirmed?.band, entries)
    val confirmed = transition.confirmed
    val name = entries.firstOrNull { it.matches(confirmed) }?.name
        ?: catalog?.stations?.firstOrNull { it.copyEntry().matches(confirmed) }?.name
    val faded = transition.state in listOf(TransitionState.Unconfirmed, TransitionState.Unresponsive, TransitionState.Disconnected)
    BoxWithConstraints(Modifier.fillMaxSize().background(Background)) {
        val windowWidth = maxWidth.value
        val compact = maxHeight.value < DesignTokens.number("breakpoint.compactHeight")
        val gap = tokenDp("space.section")
        val plateHeight = tokenDp(if (compact) "size.plate.heightCompact" else "size.plate.height")
        val showActions = transition.state == TransitionState.Unconfirmed || !connected ||
            screen.error != null || screen.recovered || state == null
        Column(Modifier.fillMaxSize().padding(tokenDp("space.window")),
            verticalArrangement = Arrangement.spacedBy(if (compact && showActions) tokenDp("space.plateGap") else gap)) {
            Row(Modifier.fillMaxWidth().height(tokenDp("size.topBar.height")).testTag("efir-topbar"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tokenDp(if (compact) "space.plateGap" else "space.window"))) {
                BasicText(confirmed?.let(::frequencyText) ?: "—",
                    Modifier.widthIn(min = tokenDp("size.plate.presetWidth")), style = designTextStyle("freq", LocalAppearanceColors.current[if (faded) "disabledText" else "text"]))
                Column(Modifier.weight(1f)) {
                  // The RDS row is measured first (fixed), so the name and status share what is left.
                  Column(Modifier.weight(1f).clipToBounds()) {
                    BasicText(name?.takeIf { it.isNotBlank() } ?: "Частота вне Подборки",
                        style = designTextStyle("title", LocalAppearanceColors.current["text"]), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = reason?.takeUnless {
                        it == DisabledReason.EmptySelection && transition.state in listOf(TransitionState.Seeking, TransitionState.SeekIncomplete)
                    }?.text ?: when (transition.state) {
                        TransitionState.Waiting -> "ожидание"
                        TransitionState.Seeking -> "идёт поиск…"
                        TransitionState.SeekIncomplete -> "Поиск не завершён"
                        else -> "${confirmed?.band?.name ?: "—"} · ${catalog?.region?.name ?: "—"}"
                    }
                    BasicText(status, Modifier.testTag("efir-status"),
                        style = designTextStyle(if (reason != null) "plate" else "body", when {
                            reason == DisabledReason.EmptySelection -> Accent
                            reason != null -> LocalAppearanceColors.current["danger"]
                            transition.state == TransitionState.Waiting || transition.state == TransitionState.Seeking -> Accent
                            else -> Muted
                        }), maxLines = 1)
                  }
                  Spacer(Modifier.height(tokenDp("size.rds.height")).testTag("rds-reserved"))
                }
                // Keep the measured width, including the pause label, while driving. alpha(0) alone still passes taps, so the buttons are also disabled.
                Row(Modifier.height(tokenDp("size.seekGroup.height"))
                    .alpha(if (driving) 0f else 1f)
                    .then(if (driving) Modifier.clearAndSetSemantics {} else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokenDp("space.plateGap"))) {
                    BasicText(if (transition.state == TransitionState.CommandPause) "Поиск · пауза" else "Поиск", style = designTextStyle("button", Muted))
                    Control("◀", connected && !driving, { actions.seek(Direction.Down) },
                        Modifier.size(tokenDp("size.seekButton.width"), tokenDp("size.seekButton.height")), description = "Поиск ◀")
                    Control("▶", connected && !driving, { actions.seek(Direction.Up) },
                        Modifier.size(tokenDp("size.seekButton.width"), tokenDp("size.seekButton.height")), description = "Поиск ▶")
                }
                Control("Стоянка", !driving, actions.parked, Modifier.size(138.dp, tokenDp("size.parkButton.height")) /* width: components.md §3, not in tokens.json */, radius = "l", parkIcon = true)
            }
            Row(Modifier.weight(1f).testTag("efir-body"), horizontalArrangement = Arrangement.spacedBy(gap)) {
                EdgeControls(Direction.Down, connected, entries.isNotEmpty(), transition.state, actions,
                    Modifier.fillMaxHeight(), playing, confirmed?.band)
                Column(Modifier.weight(1f).fillMaxHeight().testTag("efir-center"),
                    verticalArrangement = Arrangement.spacedBy(if (compact && showActions) tokenDp("space.plateGap") else gap)) {
                    Box(Modifier.fillMaxWidth().height(tokenDp(if (compact) "size.spectrum.heightCompact" else "size.spectrum.height"))
                        .testTag("spectrum-reserved"))
                    BandScale(catalog?.stations.orEmpty(), entries, transition,
                        loupeEnabled = connected && transition.state in listOf(TransitionState.Idle, TransitionState.SeekIncomplete) && transition.target == null &&
                            screen.error == null,
                        windowWidth = windowWidth, select = actions.select,
                        playing = playing, play = actions.play)
                    Row(Modifier.fillMaxWidth().height(plateHeight), horizontalArrangement = Arrangement.spacedBy(tokenDp("space.plateGap"))) {
                        var choosing by remember(state?.selectedRegion, selections?.activeName) { mutableStateOf(false) }
                        Box {
                            Control("Подборка: ${selections?.activeName ?: "—"}", true, { choosing = !choosing },
                                Modifier.width(tokenDp("size.setSwitcher.width")).fillMaxHeight())
                            if (choosing) androidx.compose.ui.window.Popup(onDismissRequest = { choosing = false }) {
                                Column(Modifier.background(Panel).verticalScroll(rememberScrollState())) {
                                    selections?.selections.orEmpty().forEach { selection ->
                                        Control("Подборка: ${selection.name}", true, {
                                            choosing = false
                                            actions.activate(selection.name)
                                        }, selected = selection.name == selections?.activeName)
                                    }
                                }
                            }
                        }
                        if (entries.isEmpty()) Column(Modifier.weight(1f).fillMaxHeight()
                            .border(tokenDp("line.edge"), LocalAppearanceColors.current["edge"], RoundedCornerShape(tokenDp("radius.m")))
                            .padding(horizontal = tokenDp("space.section")), verticalArrangement = Arrangement.Center) {
                            BasicText("Подборка пуста — добавьте станции в режиме стоянки", style = designTextStyle("plate", Muted))
                        } else StationRibbon(entries, transition, connected, actions.select, Modifier.weight(1f), plateHeight,
                            "${state?.selectedRegion}:${selections?.activeName}")
                    }
                    BasicText("Справочник", style = designTextStyle("button", Muted))
                    val visible = catalog?.stations.orEmpty().filter { it.band.name == (confirmed?.band?.name ?: state?.band?.name) }.map { it.copyEntry() }
                    if (visible.isEmpty()) BasicText("Нет Станций", style = designTextStyle("body", Muted))
                    else LazyVerticalGrid(columns = GridCells.Fixed(DesignTokens.number("size.plate.catalogColumns").toInt()),
                        modifier = Modifier.weight(1f).testTag("catalog-grid"),
                        horizontalArrangement = Arrangement.spacedBy(tokenDp("space.plateGap")),
                        verticalArrangement = Arrangement.spacedBy(tokenDp("space.plateGap"))) {
                        items(visible, key = { "${it.band}:${it.frequencyKhz}" }) { entry ->
                            StationPlate(entry, null, transition, connected, actions.select, Modifier.height(plateHeight))
                        }
                    }
                }
                EdgeControls(Direction.Up, connected, entries.isNotEmpty(), transition.state, actions,
                    Modifier.fillMaxHeight(), playing, confirmed?.band)
            }
            if (showActions) {
                // Keep messages beside the actions so the compact catalog retains a full plate row.
                Row(Modifier.fillMaxWidth().padding(horizontal = tokenDp("size.sideColumn.width") + tokenDp("space.section")),
                    horizontalArrangement = Arrangement.spacedBy(tokenDp("space.section")),
                    verticalAlignment = Alignment.CenterVertically) {
                    val buttonModifier = Modifier.height(tokenDp("size.touch.min"))
                        .widthIn(min = tokenDp("size.touch.min"))
                    if (transition.state == TransitionState.Unconfirmed)
                        Control("Проверить связь", connected, actions.check, buttonModifier)
                    if (!connected) Control("Подключить приёмник", true, actions.permission, buttonModifier)
                    val messages = buildList {
                        screen.error?.let { add(receiverText(it)) }
                        if (screen.recovered) add("Данные восстановлены: использованы значения по умолчанию")
                        if (state == null) add("Загрузка…")
                    }
                    if (messages.isNotEmpty()) BasicText(messages.joinToString(" · "), Modifier.weight(1f),
                        style = designTextStyle("cardTitle", Accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

internal fun tokenDp(path: String) = DesignTokens.number(path).dp
internal fun receiverText(text: String) = text.replace("Модуля", "приёмника").replace("Модуль", "Приёмник").replace("модуль", "приёмник")

@Composable
private fun EdgeControls(direction: Direction, connected: Boolean, hasEntries: Boolean, state: TransitionState,
                         actions: EfirActions, modifier: Modifier, playing: Boolean, band: fm.heterodyne.app.module.Band?) {
    val arrow = if (direction == Direction.Down) "◀" else "▶"
    Column(modifier.width(tokenDp("size.sideColumn.width")), verticalArrangement = Arrangement.spacedBy(tokenDp("space.section"))) {
        if (direction == Direction.Down) Control(if (playing) "Стоп" else "Старт", connected, actions.play,
            Modifier.fillMaxWidth().height(tokenDp("size.startStop.height")), selected = playing, radius = "xl", outlined = !playing, playback = playing)
        else Control("${band?.name ?: "FM"} / ${if (band == fm.heterodyne.app.module.Band.AM) "FM" else "AM"}",
            connected && band != null, actions.band, Modifier.fillMaxWidth().height(tokenDp("size.bandButton.height")), selected = true, radius = "xl", bandDisplay = band)
        Control("Станция $arrow", connected && hasEntries, { actions.station(direction) },
            Modifier.fillMaxWidth().weight(1f).heightIn(min = tokenDp("size.stationButton.minHeight")), radius = "xl", edge = true)
        Control("Шаг $arrow" + if (state == TransitionState.CommandPause) " · пауза" else "", connected,
            { actions.step(direction) }, Modifier.fillMaxWidth().height(tokenDp("size.stepButton.height")), radius = "xl", edge = true)
    }
}

@Composable
private fun StationRibbon(entries: List<StationEntry>, transition: TransitionSnapshot,
                          connected: Boolean, select: (StationEntry) -> Unit, modifier: Modifier,
                          height: androidx.compose.ui.unit.Dp, selectionKey: String) {
    val list = rememberLazyListState()
    val target = transition.target ?: transition.confirmed
    val density = LocalDensity.current
    val background = Background
    LaunchedEffect(selectionKey, target, entries) {
        val index = entries.indexOfFirst { it.matches(target) }
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
        itemsIndexed(entries, key = { _, it -> "${it.band}:${it.frequencyKhz}" }) { index, entry ->
            StationPlate(entry, index + 1, transition, connected, select,
                Modifier.width(tokenDp("size.plate.presetWidth")).height(height))
        }
    }
}

@Composable
private fun StationPlate(entry: StationEntry, number: Int?, transition: TransitionSnapshot, connected: Boolean,
                         select: (StationEntry) -> Unit, modifier: Modifier) {
    val waiting = entry.matches(transition.target) && transition.state == TransitionState.Waiting
    val current = entry.matches(transition.confirmed)
    Control("${number?.let { "$it · " } ?: ""}${entry.name?.takeIf { it.isNotBlank() } ?: "Без имени"}\n" +
        frequencyText(TuneTarget(fm.heterodyne.app.module.Band.valueOf(entry.band.name), entry.frequencyKhz)) +
        " ${entry.band}" + if (waiting) " · ожидание" else "", connected, { select(entry) },
        modifier, selected = current || entry.matches(transition.target), waiting = waiting, plate = true,
        faded = current && transition.state in listOf(TransitionState.Unconfirmed, TransitionState.Unresponsive, TransitionState.Disconnected))
}

@Composable
internal fun Control(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
                     selected: Boolean = false, description: String? = null, radius: String = "m",
                     waiting: Boolean = false, plate: Boolean = false, outlined: Boolean = false,
                     bandDisplay: fm.heterodyne.app.module.Band? = null, parkIcon: Boolean = false,
                     edge: Boolean = false, playback: Boolean? = null, faded: Boolean = false) {
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
    Box(modifier.defaultMinSize(minWidth = tokenDp("size.touch.min"), minHeight = tokenDp("size.touch.min"))
        .graphicsLayer {
            if (pressed) {
                scaleX = DesignTokens.number("state.pressed.scale")
                scaleY = scaleX
                translationY = tokenDp("state.pressed.offsetY").toPx()
            }
        }
        .clip(shape)
        .then(if (outlined) Modifier.border(tokenDp("line.stroke"), colors[if (enabled) "edge" else "disabledText"], shape) else Modifier)
        .background(if (outlined) colors["bg"] else colors[if (waiting && enabled) "accentSoft" else if (!enabled && !plate) "surface2" else tokens.background], shape)
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
            if (bandDisplay != null) contentDescription = "FM / AM"
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
        .padding(horizontal = if (bandDisplay != null) 0.dp else tokenDp(if (parkIcon) "space.window" else "space.plateGap")), contentAlignment = Alignment.Center) {
        if (edge || playback != null) Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
            val icon = if (playback != null) { if (playback) "■" else "▶" } else if (text.contains("◀")) "◀" else "▶"
            val iconSize = if (playback != null) "size.icon.l" else if (text.startsWith("Станция")) "size.icon.xl" else "font.style.band.size"
            BasicText(icon, style = designTextStyle("band", foreground).copy(fontSize = DesignTokens.number(iconSize).sp,
                lineHeight = DesignTokens.number(iconSize).sp))
            BasicText(if (edge) text.substringBefore(' ') else text, style = designTextStyle("button", foreground))
            if (text.contains("пауза")) BasicText("пауза", style = designTextStyle("label", foreground))
        } else if (bandDisplay != null) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            listOf(fm.heterodyne.app.module.Band.FM, fm.heterodyne.app.module.Band.AM).forEach { band ->
                val active = band == bandDisplay
                Box(Modifier.weight(1f).fillMaxHeight().background(colors[if (active && enabled) "accent" else "surface2"]), contentAlignment = Alignment.Center) {
                    BasicText(band.name, style = designTextStyle(if (active) "band" else "bandBack", colors[if (!enabled) "disabledText" else if (active) "onAccent" else "text"]))
                }
            }
        } else if (parkIcon) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(tokenDp("size.parkButton.iconBox")), contentAlignment = Alignment.Center) {
                BasicText("≡", style = designTextStyle("band", foreground))
            }
            BasicText(text, style = designTextStyle("button", foreground))
        } else if (plate) Column {
            BasicText(text.substringBefore('\n'), style = designTextStyle("plate", foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(text.substringAfter('\n'), style = designTextStyle("label", if (selected || !enabled || faded) foreground else colors["muted"]), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else BasicText(text, style = designTextStyle("button", foreground), maxLines = 3, overflow = TextOverflow.Ellipsis)
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
