package fm.heterodyne.app.ui

import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TuneTarget

// The product scale covers FM only.
fun bandFraction(band: Band, frequencyKhz: Int): Float =
    if (band == Band.FM) ((frequencyKhz - 87500) / 20500f).coerceIn(0f, 1f) else 0f

/** R22: station names belong to plates and the loupe, never to the band scale. */
fun scaleFrequencyLabels(band: Band, confirmedKhz: Int): List<String> {
    if (band != Band.FM) return emptyList()
    val bounds = 87500 to 108000
    return listOf(bounds.first, confirmedKhz, bounds.second).map { frequencyText(TuneTarget(band, it)) }
}

/** Keep adjacent marks disjoint, including a confirmed frequency outside the catalog. */
fun scaleMarkWidth(
    band: Band, frequencyKhz: Int, frequencies: List<Int>, width: Float,
    preferredWidth: Float, highlighted: Boolean,
): Float {
    val position = bandFraction(band, frequencyKhz)
    val gap = frequencies.asSequence().filter { it != frequencyKhz }
        .map { kotlin.math.abs(bandFraction(band, it) - position) * width }
        .minOrNull() ?: return preferredWidth
    return minOf(preferredWidth, gap * if (highlighted) .8f else .4f)
}
