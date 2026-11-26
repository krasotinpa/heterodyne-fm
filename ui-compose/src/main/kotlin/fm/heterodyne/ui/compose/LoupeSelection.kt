package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*
import kotlin.math.abs

private fun fmStations(stations: List<CatalogStationView>) = stations.sortedBy { it.frequencyKhz }

internal fun nearestLoupeStation(stations: List<CatalogStationView>, fraction: Float): CatalogStationView? {
    val frequency = 87500f + fraction.coerceIn(0f, 1f) * 20500f
    return fmStations(stations).minByOrNull { abs(it.frequencyKhz - frequency) }
}

/** Distance is measured outside the scale, in the same units as its height. */
internal fun isLoupeCancelled(y: Float, height: Float): Boolean = y < -height / 2 || y > height * 1.5f

internal fun loupeRelease(station: CatalogStationView?, cancelled: Boolean, confirmed: StationView?): CatalogStationView? =
    station?.takeUnless { cancelled || confirmed?.frequencyKhz == it.frequencyKhz }

internal fun loupeWindow(stations: List<CatalogStationView>, selected: CatalogStationView, count: Int): List<CatalogStationView?> {
    val sorted = fmStations(stations)
    val index = sorted.indexOf(selected)
    return List(count) { sorted.getOrNull(index + it - count / 2) }
}
