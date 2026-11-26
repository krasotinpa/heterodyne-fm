package fm.heterodyne.ui.compose

// The product scale covers FM only.
internal fun bandFraction(frequencyKhz: Int): Float =
    ((frequencyKhz - 87500) / 20500f).coerceIn(0f, 1f)

/** R22: station names belong to plates and the loupe, never to the band scale. */
internal fun scaleFrequencyLabels(confirmedKhz: Int): List<String> {
    val bounds = 87500 to 108000
    return listOf(bounds.first, confirmedKhz, bounds.second).map { frequencyText(it) }
}

/** Keep adjacent marks disjoint, including a confirmed frequency outside the catalog. */
internal fun scaleMarkWidth(
    frequencyKhz: Int, frequencies: List<Int>, width: Float,
    preferredWidth: Float, highlighted: Boolean,
): Float {
    val position = bandFraction(frequencyKhz)
    val gap = frequencies.asSequence().filter { it != frequencyKhz }
        .map { kotlin.math.abs(bandFraction(it) - position) * width }
        .minOrNull() ?: return preferredWidth
    return minOf(preferredWidth, gap * if (highlighted) .8f else .4f)
}
