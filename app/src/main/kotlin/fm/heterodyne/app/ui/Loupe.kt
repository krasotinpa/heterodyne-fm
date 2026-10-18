package fm.heterodyne.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.CatalogStation
import fm.heterodyne.app.stations.StationEntry

@Composable
internal fun Loupe(stations: List<CatalogStation>, entries: List<StationEntry>, selected: CatalogStation,
                   cancelled: Boolean, count: Int, modifier: Modifier = Modifier) {
    val colors = LocalAppearanceColors.current
    val shape = RoundedCornerShape(tokenDp("radius.l"))
    Column(modifier.wrapContentHeight(Alignment.Top, unbounded = true).testTag("loupe"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(tokenDp("size.loupe.height"))
            .background(colors["surface"], shape)
            .border(tokenDp("line.edge"), colors[if (cancelled) "edge" else "accent"], shape)) {
            if (cancelled) BasicText("Отмена — отпустите палец. Частота не изменится",
                Modifier.align(Alignment.Center).padding(tokenDp("space.plateGap")), designTextStyle("loupe", colors["text"]))
            else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                loupeWindow(stations, selected, count).forEachIndexed { index, station ->
                    val active = index == count / 2
                    Column(Modifier.weight(if (active) DesignTokens.number("size.loupe.selectedGrow") else 1f)
                        .fillMaxHeight().background(colors[if (active) "accent" else "surface"], shape)
                        .then(if (active) Modifier.testTag("loupe-selected").semantics(mergeDescendants = true) {} else Modifier)
                        .padding(tokenDp("space.plateGap")), verticalArrangement = Arrangement.Center) {
                        if (station != null) {
                            val star = entries.any { it.band == station.band && it.frequencyKhz == station.frequencyKhz }
                            val color = colors[if (active) "onAccent" else "text"]
                            BasicText((if (star) "★ " else "") + station.name,
                                style = designTextStyle(if (active) "loupeSel" else "loupe", color),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicText(frequencyText(TuneTarget(Band.FM, station.frequencyKhz)),
                                style = designTextStyle("loupe", color), maxLines = 1)
                        }
                    }
                }
            }
        }
        Canvas(Modifier.size(tokenDp("size.loupe.pointer"))) {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() },
                colors[if (cancelled) "edge" else "accent"])
        }
    }
}
