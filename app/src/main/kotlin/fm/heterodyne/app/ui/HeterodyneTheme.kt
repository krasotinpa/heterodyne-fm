package fm.heterodyne.app.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

class AppearanceColors(val palette: Palette, val theme: ResolvedTheme) {
    operator fun get(name: String): Color {
        val raw = DesignTokens.color(palette, theme, name)
        return if (raw.startsWith("#")) Color(0xff000000L or raw.drop(1).toLong(16)) else {
            val channels = raw.removePrefix("rgba(").removeSuffix(")").split(',').map { it.toFloat() }
            Color(channels[0] / 255f, channels[1] / 255f, channels[2] / 255f, channels[3])
        }
    }
}

val LocalAppearanceColors = staticCompositionLocalOf { AppearanceColors(Palette.Coral, ResolvedTheme.Dark) }

/** The Android night configuration is the available head-unit day/night input. */
fun headUnitNight(uiMode: Int): Boolean? = when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
    Configuration.UI_MODE_NIGHT_YES -> true
    Configuration.UI_MODE_NIGHT_NO -> false
    else -> null
}

@Composable
fun HeterodyneTheme(choice: ThemeChoice, palette: Palette, content: @Composable () -> Unit) {
    val night = headUnitNight(LocalConfiguration.current.uiMode)
    CompositionLocalProvider(LocalAppearanceColors provides AppearanceColors(palette, resolveTheme(choice, night)), content = content)
}

/** Typography and font weights follow the approved design tokens. */
fun designTextStyle(name: String, color: Color): TextStyle {
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
