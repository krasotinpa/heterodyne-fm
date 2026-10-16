package fm.heterodyne.app.ui

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.stations.CatalogStation

const val SCALE_LABEL_CHARS = 12

data class ScaleLabel(val index: Int, val row: Int, val left: Float, val right: Float)

// Display bounds follow the 0x22 frame range (G4-10): FM 87.5–108 MHz, AM 522–1620 kHz.
fun bandFraction(band: Band, frequencyKhz: Int): Float = when (band) {
    Band.FM -> (frequencyKhz - 87500) / 20500f
    Band.AM -> (frequencyKhz - 522) / 1098f
    Band.DAB -> 0f
}.coerceIn(0f, 1f)

fun scaleLabelText(name: String): String = if (name.length > SCALE_LABEL_CHARS)
    name.take(SCALE_LABEL_CHARS - 1) + "…" else name

/** Priority indices are considered first. Each label occupies the first available row. */
fun chooseLabels(
    stations: List<CatalogStation>, band: Band, widthPx: Float, charWidthPx: Float,
    gapPx: Float, priority: List<Int> = emptyList(),
): List<ScaleLabel> {
    if (widthPx <= 0 || charWidthPx <= 0) return emptyList()
    val selected = mutableListOf<ScaleLabel>()
    val order = (priority.filter { it in stations.indices } + stations.indices.sortedBy { stations[it].frequencyKhz }).distinct()
    for (index in order) {
        val station = stations[index]
        if (station.band.name != band.name) continue
        val width = scaleLabelText(station.name).length * charWidthPx
        if (width > widthPx) continue
        val left = (bandFraction(band, station.frequencyKhz) * widthPx - width / 2).coerceIn(0f, widthPx - width)
        val row = (0..1).firstOrNull { row -> selected.none {
            it.row == row && left < it.right + gapPx && it.left < left + width + gapPx
        } } ?: continue
        selected.add(ScaleLabel(index, row, left, left + width))
    }
    return selected
}
