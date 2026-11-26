package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

internal class AppearanceColors(val palette: Palette, val theme: ResolvedTheme) {
    operator fun get(name: String): Color {
        val raw = DesignTokens.color(palette, theme, name)
        return if (raw.startsWith("#")) Color(0xff000000L or raw.drop(1).toLong(16)) else {
            val channels = raw.removePrefix("rgba(").removeSuffix(")").split(',').map { it.toFloat() }
            Color(channels[0] / 255f, channels[1] / 255f, channels[2] / 255f, channels[3])
        }
    }
}

internal val LocalAppearanceColors = staticCompositionLocalOf { AppearanceColors(Palette.Coral, ResolvedTheme.Dark) }

@Composable
internal fun HeterodyneTheme(theme: ResolvedTheme, palette: Palette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppearanceColors provides AppearanceColors(palette, theme), content = content)
}

/** Typography and font weights follow the approved design tokens. */
internal fun designTextStyle(name: String, color: Color): TextStyle {
    val prefix = "font.style.$name"
    val family = DesignTokens.values.getValue("$prefix.family")
    return TextStyle(
        color = color,
        fontSize = DesignTokens.number("$prefix.size").sp,
        lineHeight = DesignTokens.number("$prefix.lineHeight").sp,
        fontWeight = FontWeight(DesignTokens.number("$prefix.weight").toInt()),
        fontFamily = if (family == "mono") DesignTypography.mono else DesignTypography.ui,
        letterSpacing = (DesignTokens.values["$prefix.letterSpacing"]?.toFloat() ?: 0f).em,
        fontFeatureSettings = "tnum",
    )
}
