package fm.heterodyne.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.stations.CatalogStation
import fm.heterodyne.app.stations.StationEntry
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun BandScale(
    stations: List<CatalogStation>, entries: List<StationEntry>, transition: TransitionSnapshot,
    modifier: Modifier = Modifier,
) {
    val confirmed = transition.confirmed
    val band = confirmed?.band
    val visible = stations.filter { it.band.name == band?.name }
    val priority = visible.indices.filter { index -> entries.any {
        it.band == visible[index].band && it.frequencyKhz == visible[index].frequencyKhz
    } } + listOfNotNull(confirmed?.let { target ->
        visible.indices.minByOrNull { abs(visible[it].frequencyKhz - target.frequencyKhz) }
    })
    val measurer = rememberTextMeasurer()
    val colors = LocalAppearanceColors.current
    val style = designTextStyle("label", colors["muted"])
    val layouts = visible.map { measurer.measure(scaleLabelText(it.name), style) }
    // Reserve enough width for the actual font, including fallback glyphs and font scaling.
    val charWidth = layouts.indices.maxOfOrNull {
        layouts[it].size.width.toFloat() / scaleLabelText(visible[it].name).length.coerceAtLeast(1)
    } ?: 1f
    val density = LocalDensity.current
    val lineHeight = layouts.maxOfOrNull { it.size.height } ?: with(density) { 20.sp.roundToPx() }
    val height = with(density) { (lineHeight * 2).toDp() + 26.dp }
    BoxWithConstraints(modifier.fillMaxWidth().height(height).testTag("band-scale")
        .semantics { contentDescription = "Шкала диапазона ${band?.name ?: "—"}" }) {
        val width = constraints.maxWidth.toFloat()
        val labels = if (band == null || band == Band.DAB) emptyList() else
            chooseLabels(visible, band, width, charWidth, with(density) { 8.dp.toPx() }, priority)
        Canvas(Modifier.fillMaxSize()) {
            val baseline = 10.dp.toPx()
            drawLine(colors["mark"], Offset(0f, baseline), Offset(size.width, baseline))
            if (band != null) visible.forEach {
                val x = bandFraction(band, it.frequencyKhz) * size.width
                drawLine(colors["mark"], Offset(x, 4.dp.toPx()), Offset(x, 18.dp.toPx()))
            }
            labels.forEach {
                drawText(layouts[it.index], topLeft = Offset(it.left, 24.dp.toPx() + it.row * lineHeight))
            }
        }
        if (confirmed != null && band != Band.DAB) {
            val faded = transition.state in listOf(TransitionState.Unconfirmed, TransitionState.Unresponsive, TransitionState.Disconnected)
            Box(Modifier.offset { IntOffset((bandFraction(confirmed.band, confirmed.frequencyKhz) * width).roundToInt() - with(density) { 1.dp.roundToPx() }, 0) }
                .width(2.dp).height(22.dp).alpha(if (faded) .45f else 1f)
                .background(colors["accent"]).testTag("band-scale-marker")
                .semantics { contentDescription = "Подтверждённая частота ${frequencyText(confirmed)}" })
        }
    }
}
