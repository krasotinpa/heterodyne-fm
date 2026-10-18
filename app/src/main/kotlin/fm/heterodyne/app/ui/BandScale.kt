package fm.heterodyne.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.stations.CatalogStation
import fm.heterodyne.app.stations.StationEntry
import kotlin.math.roundToInt

@Composable
fun BandScale(
    stations: List<CatalogStation>, entries: List<StationEntry>, transition: TransitionSnapshot,
    modifier: Modifier = Modifier,
) {
    val confirmed = transition.confirmed
    val band = confirmed?.band
    val colors = LocalAppearanceColors.current
    val density = LocalDensity.current
    Box(modifier.fillMaxWidth().height(tokenDp("size.scale.height")).testTag("band-scale")
        .background(colors["surface"], RoundedCornerShape(tokenDp("radius.l")))
        .semantics { contentDescription = "Шкала диапазона ${band?.name ?: "—"}" }) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = tokenDp("size.scale.inset"))) {
            val width = constraints.maxWidth.toFloat()
            Canvas(Modifier.fillMaxSize()) {
                val baseline = size.height / 2
                drawLine(colors["edge"], Offset(0f, baseline), Offset(size.width, baseline), tokenDp("line.tick").toPx())
                if (band != null && band != Band.DAB) {
                    val ticks = if (band == Band.FM) 41 else 61
                    repeat(ticks + 1) { index ->
                        val x = index * size.width / ticks
                        val height = tokenDp(if (index % 5 == 0) "size.scale.tickMajor" else "size.scale.tickMinor").toPx()
                        drawLine(colors["edge"], Offset(x, baseline - height / 2), Offset(x, baseline + height / 2), tokenDp("size.scale.tickWidth").toPx())
                    }
                    stations.filter { it.band.name == band.name }.forEach { station ->
                        val preset = entries.any { it.band == station.band && it.frequencyKhz == station.frequencyKhz }
                        val prefix = if (preset) "size.scale.markPreset" else "size.scale.markStation"
                        val x = bandFraction(band, station.frequencyKhz) * size.width
                        val height = tokenDp("$prefix.1").toPx()
                        drawLine(colors[if (preset) "accent" else "mark"], Offset(x, baseline - height / 2),
                            Offset(x, baseline + height / 2), tokenDp("$prefix.0").toPx())
                    }
                }
            }
            if (confirmed != null && band != Band.DAB) {
                val labels = scaleFrequencyLabels(confirmed.band, confirmed.frequencyKhz)
                val fraction = bandFraction(confirmed.band, confirmed.frequencyKhz)
                val faded = transition.state in listOf(TransitionState.Unconfirmed, TransitionState.Unresponsive, TransitionState.Disconnected)
                val sliderWidth = with(density) { tokenDp("size.scale.slider").toPx() }
                Box(Modifier.offset { IntOffset((fraction * width - sliderWidth / 2).roundToInt(), 0) }
                    .width(tokenDp("size.scale.slider")).fillMaxHeight()
                    .background(colors[if (faded) "disabledText" else "accent"]).testTag("band-scale-marker")
                    .semantics { contentDescription = "Подтверждённая частота ${frequencyText(confirmed)}" })
                BasicText(labels.first(), Modifier.align(Alignment.BottomStart), designTextStyle("label", colors["muted"]))
                BasicText(labels.last(), Modifier.align(Alignment.BottomEnd), designTextStyle("label", colors["muted"]))
                // Keep the frequency inside the scale at both ends of the band.
                Box(Modifier.fillMaxWidth().align(Alignment.TopStart), contentAlignment = androidx.compose.ui.BiasAlignment(fraction * 2 - 1, -1f)) {
                    BasicText(labels[1], style = designTextStyle("label", colors[if (faded) "disabledText" else "accentText"]))
                }
            }
        }
    }
}
