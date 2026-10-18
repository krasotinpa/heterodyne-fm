package fm.heterodyne.app.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.ResourceFont
import fm.heterodyne.app.R
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TypographyTest {
    private fun projectFile(path: String): File = listOf(File(path), File("../$path"))
        .first { it.isFile }

    @OptIn(ExperimentalTextApi::class)
    @Test fun everyStyleUsesBundledFamilyAndExactTokenWeight() {
        val fontTokens = JSONObject(projectFile("docs/design/v1/tokens.json").readText()).getJSONObject("font")
        val styles = fontTokens.getJSONObject("style")
        styles.keys().forEach { name ->
            val token = styles.getJSONObject(name)
            val style = designTextStyle(name, Color.Black)
            assertEquals("$name weight", token.getInt("weight"), style.fontWeight?.weight)
            assertEquals("$name size", token.getDouble("size").toFloat(), style.fontSize.value, 0f)
            assertEquals("$name line height", token.getDouble("lineHeight").toFloat(), style.lineHeight.value, 0f)
            val family = style.fontFamily
            assertTrue("$name must use bundled fonts", family is FontListFontFamily)
            val fonts = (family as FontListFontFamily).fonts
            val resource = fonts.filterIsInstance<ResourceFont>().single { it.weight == style.fontWeight }
            assertEquals(if (token.getString("family") == "mono") R.font.jetbrains_mono else R.font.golos_text, resource.resId)
            val axis = resource.variationSettings.settings.single { it.axisName == "wght" }
            assertEquals(token.getInt("weight").toFloat(), axis.toVariationValue(Density(1f)), 0f)
            val fallbacks = fontTokens.getJSONObject("family").getJSONObject(token.getString("family")).getJSONArray("fallback")
            for (index in 0 until fallbacks.length()) {
                assertTrue("$name must include fallback ${fallbacks.getString(index)}", fonts.contains(
                    Font(DeviceFontFamilyName(fallbacks.getString(index)), weight = FontWeight(token.getInt("weight")))
                ))
            }
        }
    }

    @Test fun bundledFontsHaveWeightAxisCoveringTypographyAndLicenses() {
        listOf("golos_text" to "golos-text", "jetbrains_mono" to "jetbrains-mono").forEach { (font, license) ->
            val data = ByteBuffer.wrap(projectFile("app/src/main/res/font/$font.ttf").readBytes()).order(ByteOrder.BIG_ENDIAN)
            assertEquals("TrueType header: $font", 0x00010000, data.getInt(0))
            val count = data.getShort(4).toInt() and 0xffff
            val fvar = (0 until count).map { 12 + 16 * it }.first { data.getInt(it) == 0x66766172 }
            val offset = data.getInt(fvar + 8)
            val axesOffset = data.getShort(offset + 4).toInt() and 0xffff
            val axisCount = data.getShort(offset + 8).toInt() and 0xffff
            val axisSize = data.getShort(offset + 10).toInt() and 0xffff
            val weightAxis = (0 until axisCount).map { offset + axesOffset + axisSize * it }
                .first { data.getInt(it) == 0x77676874 }
            val min = data.getInt(weightAxis + 4) / 65536f
            val max = data.getInt(weightAxis + 12) / 65536f
            assertTrue("$font must cover 400–800", min <= 400 && max >= 800)
            val text = projectFile("app/src/main/assets/licenses/$license-OFL.txt").readText()
            assertTrue(text.contains("SIL OPEN FONT LICENSE Version 1.1"))
            assertTrue(text.contains("Copyright"))
            assertTrue(text.contains("PERMISSION & CONDITIONS"))
            assertTrue(text.contains("DISCLAIMER"))
        }
    }
}
