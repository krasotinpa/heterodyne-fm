package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class DisabledReasonTest {

    @Test fun disabledTextAndIconsMeetContrastOnSurfaceInAllThemesAndPalettes() {
        for (palette in Palette.entries) for (theme in ResolvedTheme.entries) {
            fun luminance(token: String): Double {
                val channels = DesignTokens.color(palette, theme, token).removePrefix("#").chunked(2)
                    .map { it.toInt(16) / 255.0 }
                    .map { if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4) }
                return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722
            }
            val foreground = luminance("disabledText")
            val background = luminance("surface")
            val contrast = (maxOf(foreground, background) + .05) / (minOf(foreground, background) + .05)
            assertTrue("$palette/$theme disabled text: $contrast", contrast >= 4.5)
            assertTrue("$palette/$theme disabled icon: $contrast", contrast >= 3)
        }
    }
}
