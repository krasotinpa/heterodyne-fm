package fm.heterodyne.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val Background = Color(0xff121417)
private val Panel = Color(0xff1e2228)
private val Accent = Color(0xfff2a33a)
private val Muted = Color(0xffa7adb7)

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
) {
    val state = screen.state
    val catalog = screen.catalogs.firstOrNull { it.region.id == state?.selectedRegion }
    val sets = state?.regions?.get(state.selectedRegion)
    val entries = sets?.active?.entries.orEmpty()
    val confirmed = transition.confirmed
    val name = entries.firstOrNull { it.matches(confirmed) }?.name
        ?: catalog?.stations?.firstOrNull { it.copyEntry().matches(confirmed) }?.name
    val faded = transition.state in listOf(TransitionState.Unconfirmed, TransitionState.Unresponsive, TransitionState.Disconnected)
    Row(Modifier.fillMaxSize().background(Background).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EdgeControls(Direction.Down, connected, entries.isNotEmpty(), transition.state, actions, Modifier.fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).alpha(if (faded) .45f else 1f)) {
                    BasicText(confirmed?.let(::frequencyText) ?: "—", style = TextStyle(Color.White, 56.sp, fontFamily = FontFamily.Monospace))
                    Label(name?.takeIf { it.isNotBlank() } ?: "Частота вне Набора", 22)
                    Label("${confirmed?.band?.name ?: "—"} · ${catalog?.region?.name ?: "—"}", 18, Muted)
                }
                Control("FM / AM", connected && confirmed != null, actions.band)
                Control("Поиск ◀" + if (transition.state == TransitionState.CommandPause) " · пауза" else "", connected, { actions.seek(Direction.Down) })
                Control("Поиск ▶" + if (transition.state == TransitionState.CommandPause) " · пауза" else "", connected, { actions.seek(Direction.Up) })
                Control("Стоянка", true, actions.parked)
            }
            val banner = transition.banner ?: when (transition.state) {
                TransitionState.Waiting -> "ожидание"
                TransitionState.CommandPause -> "Пауза команды: Шаг / Поиск"
                else -> null
            }
            if (banner != null) {
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Label(banner, 22, Accent, Modifier.weight(1f))
                    if (transition.state == TransitionState.Unconfirmed) Control("Проверить связь", connected, actions.check)
                    if (!connected) Control("Подключить Модуль", true, actions.permission)
                }
            }
            screen.error?.let { Label(it, 18, Accent) }
            if (screen.recovered) Label("Данные восстановлены: использованы значения по умолчанию", 18, Accent)
            if (state == null) Label("Загрузка…", 22)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sets?.sets.orEmpty(), key = { it.name }) { set ->
                    Control("Набор: ${set.name}", true, { actions.activate(set.name) }, selected = set.name == sets?.activeName)
                }
            }
            StationRibbon("Набор", entries, transition, connected, actions.select, Modifier.weight(1f))
            StationRibbon("Справочник", catalog?.stations.orEmpty().map { it.copyEntry() }, transition, connected, actions.select, Modifier.weight(1f))
            Control(if (playing) "Стоп" else "Играть", connected, actions.play)
        }
        EdgeControls(Direction.Up, connected, entries.isNotEmpty(), transition.state, actions, Modifier.fillMaxHeight())
    }
}

@Composable
private fun EdgeControls(direction: Direction, connected: Boolean, hasEntries: Boolean, state: TransitionState,
                         actions: EfirActions, modifier: Modifier) {
    val arrow = if (direction == Direction.Down) "◀" else "▶"
    Column(modifier.width(104.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Control("Станция $arrow", connected && hasEntries, { actions.station(direction) }, Modifier.fillMaxWidth().weight(1f))
        Control("Шаг $arrow" + if (state == TransitionState.CommandPause) " · пауза" else "", connected,
            { actions.step(direction) }, Modifier.fillMaxWidth().height(112.dp))
    }
}

@Composable
private fun StationRibbon(title: String, entries: List<StationEntry>, transition: TransitionSnapshot,
                          connected: Boolean, select: (StationEntry) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(title, 18, Muted)
        if (entries.isEmpty()) Label("Нет Станций", 20, Muted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { "${it.band}:${it.frequencyKhz}" }) { entry ->
                val current = entry.matches(transition.confirmed)
                val target = entry.matches(transition.target)
                val waiting = target && transition.state == TransitionState.Waiting
                Control(
                    "${entry.name?.takeIf { it.isNotBlank() } ?: "Без имени"}\n" +
                        frequencyText(TuneTarget(fm.heterodyne.app.module.Band.valueOf(entry.band.name), entry.frequencyKhz)) +
                        " ${entry.band}" + if (waiting) " · ожидание" else "",
                    connected, { select(entry) }, Modifier.width(184.dp).height(104.dp)
                        .alpha(if (current && transition.state in listOf(TransitionState.Unconfirmed, TransitionState.Unresponsive, TransitionState.Disconnected)) .45f else 1f),
                    selected = current || target,
                )
            }
        }
    }
}

@Composable
internal fun Control(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    Box(modifier.defaultMinSize(minWidth = 48.dp, minHeight = 56.dp)
        .background(if (selected) Color(0xff2a2213) else Panel, RoundedCornerShape(12.dp))
        .border(1.dp, if (selected) Accent else Color(0xff3a3f47), RoundedCornerShape(12.dp))
        .semantics { this.selected = selected }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp), contentAlignment = Alignment.Center) {
        Label(text, 18, if (enabled) Color.White else Muted)
    }
}

@Composable
internal fun Label(text: String, size: Int, color: Color = Color.White, modifier: Modifier = Modifier) {
    BasicText(text, modifier, TextStyle(color, size.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
}
