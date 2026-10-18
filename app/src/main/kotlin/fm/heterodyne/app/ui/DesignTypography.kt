package fm.heterodyne.app.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import fm.heterodyne.app.R

/** Variable fonts require API 26; the application has minSdk 30. */
internal object DesignTypography {
    val ui = family("ui", R.font.golos_text)
    val mono = family("mono", R.font.jetbrains_mono)

    @OptIn(ExperimentalTextApi::class)
    private fun family(name: String, resource: Int): FontFamily {
        val values = DesignTokens.values
        val weights = values.keys.filter {
            it.startsWith("font.style.") && it.endsWith(".family") && values[it] == name
        }.map { DesignTokens.number(it.removeSuffix("family") + "weight").toInt() }.distinct()
        val fallbacks = values.filterKeys { it.startsWith("font.family.$name.fallback.") }.values
        return FontFamily(weights.flatMap { weight ->
            listOf(Font(
                resId = resource,
                weight = FontWeight(weight),
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
                // Try the bundled file first; use the named system fonts if loading fails.
                loadingStrategy = FontLoadingStrategy.OptionalLocal,
            )) + fallbacks.map { Font(DeviceFontFamilyName(it), weight = FontWeight(weight)) }
        })
    }
}
