package fm.heterodyne.app.ui

import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.CatalogStation
import fm.heterodyne.app.stations.StationEntry
import kotlin.math.abs

private fun fmStations(stations: List<CatalogStation>) = stations.sortedBy { it.frequencyKhz }

fun nearestLoupeStation(stations: List<CatalogStation>, fraction: Float): CatalogStation? {
    val frequency = 87500f + fraction.coerceIn(0f, 1f) * 20500f
    return fmStations(stations).minByOrNull { abs(it.frequencyKhz - frequency) }
}

/** Distance is measured outside the scale, in the same units as its height. */
fun isLoupeCancelled(y: Float, height: Float): Boolean = y < -height / 2 || y > height * 1.5f

fun loupeRelease(station: CatalogStation?, cancelled: Boolean, confirmed: TuneTarget?): StationEntry? =
    station?.takeUnless { cancelled || (confirmed?.band == fm.heterodyne.app.module.Band.FM && confirmed.frequencyKhz == it.frequencyKhz) }?.copyEntry()

fun loupeWindow(stations: List<CatalogStation>, selected: CatalogStation, count: Int): List<CatalogStation?> {
    val sorted = fmStations(stations)
    val index = sorted.indexOf(selected)
    return List(count) { sorted.getOrNull(index + it - count / 2) }
}
