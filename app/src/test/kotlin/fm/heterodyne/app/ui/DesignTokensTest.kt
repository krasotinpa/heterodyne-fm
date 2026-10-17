package fm.heterodyne.app.ui

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class DesignTokensTest {
    private fun designFile(name: String): File = listOf(File("../docs/design/v1/$name"), File("docs/design/v1/$name"))
        .first { it.isFile }

    @Test fun kotlinTokensMatchApprovedJsonIncludingEveryPaletteAndTypography() {
        val expected = mutableMapOf<String, String>()
        fun flatten(value: Any, path: String) {
            when (value) {
                is JSONObject -> value.keys().forEach { flatten(value.get(it), if (path.isEmpty()) it else "$path.$it") }
                is JSONArray -> (0 until value.length()).forEach { flatten(value.get(it), "$path.$it") }
                else -> expected[path] = value.toString()
            }
        }
        val json = JSONObject(designFile("tokens.json").readText())
        listOf("color", "font", "size", "space", "radius", "line", "shadow", "state", "motion", "breakpoint")
            .forEach { flatten(json.get(it), it) }
        assertEquals(expected, DesignTokens.values)
        assertEquals("coral", DesignTokens.values.getValue("color.defaultPalette"))
        Palette.entries.forEach { palette -> ResolvedTheme.entries.forEach { theme ->
            assertEquals(palette.title, DesignTokens.values.getValue("color.palettes.${palette.token}.name"))
            assertEquals(15, DesignTokens.values.keys.count { it.startsWith("color.palettes.${palette.token}.${theme.token}.") })
        } }
    }

    @Test fun allDocumentedContrastPairsMeetWcagInEveryPaletteAndTheme() {
        var threshold = 0.0
        var checked = 0
        designFile("tokens.md").readLines().forEach { line ->
            if (line.startsWith("### Текст")) threshold = 4.5
            if (line.startsWith("### Значок") || line.startsWith("### Граница")) threshold = 3.0
            val pair = Regex("^\\| `([^`]+) / ([^`]+)`").find(line)
            if (threshold > 0 && pair != null) {
                val foreground = pair.groupValues[1]
                val background = pair.groupValues[2]
                Palette.entries.forEach { palette -> ResolvedTheme.entries.forEach { theme ->
                    fun rgb(name: String) = DesignTokens.color(palette, theme, name).removePrefix("#")
                        .chunked(2).map { it.toInt(16) / 255.0 }
                    val bg = rgb(background)
                    val fg = rgb(foreground.substringBefore('·')).mapIndexed { i, channel ->
                        if ('·' in foreground) channel * .85 + bg[i] * .15 else channel
                    }
                    fun luminance(channels: List<Double>): Double = channels.map {
                        if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4)
                    }.let { it[0] * .2126 + it[1] * .7152 + it[2] * .0722 }
                    val a = luminance(fg); val b = luminance(bg)
                    val ratio = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
                    assertTrue("$palette/$theme $foreground/$background: $ratio < $threshold", ratio >= threshold)
                    checked++
                } }
            }
        }
        assertEquals(240, checked)
    }
}
