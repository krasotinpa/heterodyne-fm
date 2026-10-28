package fm.heterodyne.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.CatalogStation
import fm.heterodyne.app.stations.StationEntry
import kotlin.math.roundToInt

@Composable
fun BandScale(
    stations: List<CatalogStation>, entries: List<StationEntry>, transition: TransitionSnapshot,
    modifier: Modifier = Modifier,
    loupeEnabled: Boolean = false,
    windowWidth: Float = 1100f,
    select: (StationEntry) -> Unit = {},
    marker: ScaleMarker = ScaleMarker.Pin,
    playing: Boolean = false,
    play: () -> Unit = {},
    saved: TuneTarget? = null,
) {
    val confirmed = transition.confirmed?.takeIf { it.band == Band.FM }
    val cursor = scaleCursor(transition.confirmed, saved, transition.state)
        ?.takeUnless { transition.confirmed == null && transition.target != null }
    val band = cursor?.target?.band ?: transition.target?.band?.takeIf { it == Band.FM && marker != ScaleMarker.Stripe }
    val colors = LocalAppearanceColors.current
    val density = LocalDensity.current
    val enabled = loupeEnabled && confirmed?.band == Band.FM && stations.isNotEmpty()
    val currentSelect by rememberUpdatedState(select)
    val currentPlaying by rememberUpdatedState(playing)
    val currentPlay by rememberUpdatedState(play)
    var preview by remember(enabled, stations, transition) { mutableStateOf<CatalogStation?>(null) }
    var cancelled by remember(enabled, stations, transition) { mutableStateOf(false) }
    val inset = with(density) { tokenDp("size.scale.inset").toPx() }
    Box(modifier.zIndex(1f).fillMaxWidth().height(tokenDp("size.scale.height")).testTag("band-scale")
        .background(colors["surface"], RoundedCornerShape(tokenDp("radius.l")))
        .pointerInput(enabled, stations, transition, inset) {
            if (enabled) awaitEachGesture {
                val down = awaitFirstDown()
                fun update(x: Float, y: Float) {
                    preview = nearestLoupeStation(stations, (x - inset) / (size.width - 2 * inset).coerceAtLeast(1f))
                    cancelled = isLoupeCancelled(y, size.height.toFloat())
                }
                update(down.position.x, down.position.y)
                down.consume()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        update(change.position.x, change.position.y)
                        change.consume()
                        if (!change.pressed) {
                            val selection = loupeRelease(preview, cancelled, transition.confirmed)
                            if (selection != null) currentSelect(selection)
                            else if (!cancelled && !currentPlaying && preview?.let {
                                confirmed?.band == Band.FM && it.frequencyKhz == confirmed.frequencyKhz
                            } == true) currentPlay()
                            break
                        }
                    }
                } finally { preview = null; cancelled = false }
            }
        }
        .semantics { contentDescription = "Шкала диапазона ${band?.name ?: "—"}" }) {
        preview?.let { station ->
            val count = DesignTokens.number(if (windowWidth < DesignTokens.number("breakpoint.narrowLoupeWidth"))
                "size.loupe.stationsNarrow" else "size.loupe.stations").toInt()
            Loupe(stations, entries, station, cancelled, count, Modifier.fillMaxWidth().offset(y = -(
                tokenDp("size.loupe.height") + tokenDp("size.loupe.gapToScale"))))
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = tokenDp("size.scale.inset"))) {
            val width = constraints.maxWidth.toFloat()
            val visiblePreview = preview?.takeUnless { cancelled }
            val frequencies = (stations.map { it.frequencyKhz } +
                listOfNotNull(cursor?.target?.frequencyKhz, visiblePreview?.frequencyKhz)).distinct()
            fun markWidth(frequency: Int, preferred: Float, highlighted: Boolean): Float =
                scaleMarkWidth(band ?: Band.FM, frequency, frequencies, width, preferred, highlighted)
            fun markerPosition(target: fm.heterodyne.app.module.TuneTarget) = Modifier.offset {
                val markerWidth = with(density) { tokenDp("size.scale.marker.width").toPx() }
                val top = with(density) {
                    (tokenDp("size.scale.height") - tokenDp("size.scale.marker.baselineInset") - tokenDp("size.scale.marker.height")).toPx()
                }
                IntOffset((bandFraction(target.band, target.frequencyKhz) * width - markerWidth / 2).roundToInt(), top.roundToInt())
            }
            Canvas(Modifier.fillMaxSize()) {
                val baseline = if (marker == ScaleMarker.Stripe) size.height / 2 else size.height - tokenDp("size.scale.marker.baselineInset").toPx()
                drawLine(colors["edge"], Offset(0f, baseline), Offset(size.width, baseline), tokenDp("line.tick").toPx())
                if (band == Band.FM) {
                    val ticks = 41
                    repeat(ticks + 1) { index ->
                        val x = index * size.width / ticks
                        val height = tokenDp(if (index % 5 == 0) "size.scale.tickMajor" else "size.scale.tickMinor").toPx()
                        drawLine(colors["edge"], Offset(x, baseline - height / 2), Offset(x, baseline + height / 2), tokenDp("size.scale.tickWidth").toPx())
                    }
                    stations.forEach { station ->
                        if (station.frequencyKhz == confirmed?.frequencyKhz) return@forEach
                        val preset = entries.any { it.frequencyKhz == station.frequencyKhz }
                        val prefix = if (preset) "size.scale.markPreset" else "size.scale.markStation"
                        val x = bandFraction(band, station.frequencyKhz) * size.width
                        val height = tokenDp("$prefix.1").toPx()
                        drawLine(colors[if (preset) "accent" else "mark"], Offset(x, baseline - height / 2),
                            Offset(x, baseline + height / 2), markWidth(station.frequencyKhz, tokenDp("$prefix.0").toPx(), false))
                    }
                }
            }
            if (confirmed == null && marker != ScaleMarker.Stripe) {
                transition.target?.takeIf { it.band == Band.FM }?.let {
                    ScaleMarkerShape(marker, it, true, false, markerPosition(it).testTag("band-scale-preview-target"))
                }
            }
            if (cursor != null && band == Band.FM) {
                val cursorTarget = cursor.target
                val labels = scaleFrequencyLabels(cursorTarget.band, cursorTarget.frequencyKhz)
                val fraction = bandFraction(cursorTarget.band, cursorTarget.frequencyKhz)
                val faded = cursor.faded
                val stripeWidth = markWidth(cursorTarget.frequencyKhz,
                    with(density) { tokenDp("size.scale.confirmed.0").toPx() }, true)
                if (marker == ScaleMarker.Stripe) {
                    Box(Modifier.align(Alignment.CenterStart)
                        .offset { IntOffset((fraction * width - stripeWidth / 2).roundToInt(), 0) }
                        .width(with(density) { stripeWidth.toDp() }).height(tokenDp("size.scale.confirmed.1"))
                        .background(colors[if (faded) "disabledText" else "accent"])
                        .testTag("band-scale-confirmed-stripe")
                        .semantics { contentDescription = "${if (transition.confirmed == null) "Сохранённая" else "Подтверждённая"} Станция ${frequencyText(cursorTarget)}" })
                    visiblePreview?.let { station ->
                        val targetWidth = markWidth(station.frequencyKhz,
                            with(density) { tokenDp("size.scale.preview.0").toPx() }, true)
                        val targetFraction = bandFraction(cursorTarget.band, station.frequencyKhz)
                        // Open outline differs from the solid cursor stripe even at the same frequency.
                        Box(Modifier.align(Alignment.CenterStart)
                            .offset { IntOffset((targetFraction * width - targetWidth / 2).roundToInt(), 0) }
                            .width(with(density) { targetWidth.toDp() }).height(tokenDp("size.scale.preview.1"))
                            .border(with(density) { minOf(1.dp.toPx(), targetWidth / 4).toDp() }, colors["accentText"])
                            .testTag("band-scale-preview-target")
                            .semantics { contentDescription = "Цель Лупы ${frequencyText(fm.heterodyne.app.module.TuneTarget(cursorTarget.band, station.frequencyKhz))}" })
                    }
                    // Draw the slider last so its full-height stem remains visible.
                    val sliderWidth = minOf(with(density) { tokenDp("size.scale.slider").toPx() }, stripeWidth / 2)
                    Box(Modifier.offset { IntOffset((fraction * width - sliderWidth / 2).roundToInt(), 0) }
                        .width(with(density) { sliderWidth.toDp() }).fillMaxHeight()
                        .background(colors[if (faded) "disabledText" else "accent"]).testTag("band-scale-marker")
                        .semantics { contentDescription = "${if (transition.confirmed == null) "Сохранённая" else "Подтверждённая"} частота ${frequencyText(cursorTarget)}" })
                } else {
                    ScaleMarkerShape(marker, cursorTarget, false, faded,
                        markerPosition(cursorTarget).testTag("band-scale-marker"))
                    val target = visiblePreview?.let { fm.heterodyne.app.module.TuneTarget(cursorTarget.band, it.frequencyKhz) }
                        ?: transition.target?.takeIf { it.band == cursorTarget.band && it != cursorTarget }
                    target?.let {
                        ScaleMarkerShape(marker, it, true, false,
                            markerPosition(it).testTag("band-scale-preview-target"))
                    }
                }
                if (transition.confirmed != null) {
                    BasicText(labels.first(), Modifier.align(Alignment.BottomStart), designTextStyle("label", colors["muted"]))
                    BasicText(labels.last(), Modifier.align(Alignment.BottomEnd), designTextStyle("label", colors["muted"]))
                    // Keep the frequency inside the scale at both ends of the band.
                    if (marker == ScaleMarker.Stripe) Box(Modifier.fillMaxWidth().align(Alignment.TopStart), contentAlignment = androidx.compose.ui.BiasAlignment(fraction * 2 - 1, -1f)) {
                        BasicText(labels[1], style = designTextStyle("label", colors[if (faded) "disabledText" else "accentText"]))
                    } else if (marker != ScaleMarker.Flag) {
                        // Place the label beside the head, keeping it inside either band edge.
                        val labelWidth = with(density) { tokenDp("size.scale.marker.width").toPx() }
                        val labelX = if (fraction <= .5f) fraction * width + labelWidth / 2
                            else fraction * width - labelWidth * 1.5f
                        BasicText(labels[1].substringBefore(' '), Modifier.offset {
                            IntOffset(labelX.coerceIn(0f, (width - labelWidth).coerceAtLeast(0f)).roundToInt(), 0)
                        }.width(tokenDp("size.scale.marker.width")),
                            style = designTextStyle("label", colors[if (faded) "disabledText" else "accentText"]))
                    }
                }
            }
        }
    }
}
