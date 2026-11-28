package fm.heterodyne.ui.compose

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import fm.heterodyne.ui.contract.CatalogStationView
import fm.heterodyne.ui.contract.ExportState
import fm.heterodyne.ui.contract.RadioUiState
import fm.heterodyne.ui.contract.ResolvedTheme
import fm.heterodyne.ui.contract.TuningPhase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

private const val COMPACT = "ru-rRU-w1024dp-h600dp-land-mdpi"
private const val STANDARD = "ru-rRU-w1280dp-h720dp-land-mdpi"
private const val WIDE = "ru-rRU-w1920dp-h1080dp-land-mdpi"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = STANDARD)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VisualRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val confirmed = station(97700, "Радио Маяк")
    private val stations = stationsView(
        entries = listOf(station(87500, "Первая"), confirmed),
        catalog = listOf(CatalogStationView("mayak", 97700, "Радио Маяк"),
            CatalogStationView("vesna", 101700, "Радио Весна")),
    )
    private lateinit var focusManager: FocusManager

    @Before fun startFromFixedTime() {
        assertTrue(SystemClock.setCurrentTimeMillis(1_000L))
    }

    @Test fun lightEfirMatchesReference() = emptyEfir(ResolvedTheme.Light, "efir-light")
    @Test fun darkEfirMatchesReference() = emptyEfir(ResolvedTheme.Dark, "efir-dark")

    @Test @Config(qualifiers = COMPACT)
    fun compactLightEfir() = efir(ResolvedTheme.Light, 1f)

    @Test @Config(qualifiers = COMPACT)
    fun compactDarkLargeFontEfir() = efir(ResolvedTheme.Dark, 1.3f)

    @Test @Config(qualifiers = COMPACT)
    fun compactLightDisconnectedEfir() {
        val state = sampleRadio(stations, tuning(TuningPhase.Disconnected, saved = confirmed), connected = false)
        render(state, ResolvedTheme.Light, 1.3f)
        checkReference("efir-disconnected", ResolvedTheme.Light) {
            compose.onNodeWithTag("efir-status").assertTextEquals("Приёмник отключён")
            compose.onNodeWithText("Подключить приёмник").assertIsEnabled()
        }
    }

    @Test @Config(qualifiers = COMPACT)
    fun compactDarkParking() = parkingMenu(ResolvedTheme.Dark, 1f)

    @Test @Config(qualifiers = COMPACT)
    fun compactLightFrequencyErrorMatchesReference() = frequencyError(ResolvedTheme.Light)

    @Test @Config(qualifiers = COMPACT)
    fun compactDarkFrequencyErrorMatchesReference() = frequencyError(ResolvedTheme.Dark)

    @Test fun standardDarkLargeFontEfir() = efir(ResolvedTheme.Dark, 1.3f)
    @Test fun standardLightLargeFontDiagnostics() = diagnostics(ResolvedTheme.Light, 1.3f)

    @Test @Config(qualifiers = WIDE)
    fun wideLightEditor() {
        render(parkedState(), ResolvedTheme.Light, 1f)
        checkReference("editor", ResolvedTheme.Light) { openEditor() }
    }

    @Test @Config(qualifiers = WIDE)
    fun wideDarkDiagnostics() = diagnostics(ResolvedTheme.Dark, 1f)

    @Test @Config(qualifiers = WIDE)
    fun wideLightLargeFontParking() = parkingMenu(ResolvedTheme.Light, 1.3f)

    @Test @Config(qualifiers = WIDE)
    fun wideDarkLargeFontEfir() = efir(ResolvedTheme.Dark, 1.3f)

    private fun emptyEfir(theme: ResolvedTheme, reference: String) {
        render(sampleRadio(tuning = tuning(confirmed = station(97700))), theme, 1f)
        checkReference("efir-empty", theme, reference) {
            compose.onNodeWithTag("efir-frequency").assertTextEquals("97,7")
        }
    }

    private fun efir(theme: ResolvedTheme, fontScale: Float) {
        render(sampleRadio(stations, tuning(confirmed = confirmed)), theme, fontScale)
        checkReference("efir", theme) {
            compose.onNodeWithTag("efir-name").assertTextEquals("Радио Маяк")
        }
    }

    private fun parkedState() = parking(stations, confirmed, connected = true)

    private fun parkingMenu(theme: ResolvedTheme, fontScale: Float) {
        render(parkedState(), theme, fontScale)
        checkReference("parking", theme) {
            compose.onNodeWithTag("parking-menu").assertExists()
        }
    }

    private fun openEditor() {
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Справочник →").assertExists()
    }

    private fun frequencyError(theme: ResolvedTheme) {
        render(parkedState(), theme, 1.3f)
        checkReference("frequency-error", theme) {
            openEditor()
            compose.onNodeWithText("Добавить Станцию").performClick()
            compose.onNodeWithTag("station-name").performTextReplacement("Новая")
            compose.onNodeWithTag("station-frequency").performTextReplacement("200,0")
            compose.onNodeWithText("Сохранить Станцию").performClick()
            compose.runOnIdle { focusManager.clearFocus() }
            compose.onNodeWithText("Введите FM-частоту 87,5–108,0 МГц с шагом 0,1").assertExists()
        }
    }

    private fun diagnostics(theme: ResolvedTheme, fontScale: Float) {
        val state = parkedState().copy(diagnostics = sampleDiagnostics.copy(
            buildVersion = "0.1.0-test",
            export = ExportState.Exported("Download/heterodyne-log.txt", 2048),
        ))
        render(state, theme, fontScale)
        checkReference("diagnostics", theme) {
            compose.onNodeWithText("Диагностика").performClick()
            compose.onNodeWithTag("diagnostics-version").assertExists()
        }
    }

    private fun render(state: RadioUiState, theme: ResolvedTheme, fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            assertEquals(fontScale, LocalDensity.current.fontScale)
            assertEquals(1f, LocalDensity.current.density)
            focusManager = LocalFocusManager.current
            RadioSnapshot(state.copy(settings = state.settings.copy(resolvedTheme = theme)), {})
        }
    }

    private fun checkReference(
        scenario: String,
        theme: ResolvedTheme,
        legacyReference: String? = null,
        exercise: () -> Unit,
    ) {
        val scenarioFailure = try {
            exercise()
            null
        } catch (failure: AssertionError) {
            // Preserve the last rendered frame when a Compose assertion fails.
            failure
        }
        val configuration = compose.activity.resources.configuration
        val width = configuration.screenWidthDp
        val height = configuration.screenHeightDp
        val actual = compose.runOnIdle {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }
        val pixels = actual.readPixels()
        val name = "$scenario-${width}x$height-${theme.name.lowercase()}-${configuration.fontScale}"
        val hasRenderedUi = pixels.any { it != pixels[0] }
        val reference = legacyReference ?: name
        if (System.getenv("HETERODYNE_RECORD_SCREENSHOTS") == "1") {
            check(System.getenv("CI") != "true") { "Recording references is disabled on CI" }
            if (scenarioFailure != null) throw scenarioFailure
            assertTrue("$name must contain rendered UI", hasRenderedUi)
            assertEquals(width, actual.width)
            assertEquals(height, actual.height)
            write(actual, File("src/test/resources/screenshots/$reference.png"))
            return
        }
        val expected = javaClass.getResourceAsStream("/screenshots/$reference.png")?.use(BitmapFactory::decodeStream)
        val report = "build/reports/screenshots/$name-api${Build.VERSION.SDK_INT}"
        if (expected == null) {
            write(actual, File("$report-actual.png"))
            throw AssertionError("Missing reference $reference.png; captured $report-actual.png", scenarioFailure)
        }
        val matches = hasRenderedUi && actual.width == width && actual.height == height && expected.sameAs(actual)
        if (matches && scenarioFailure == null) return

        write(actual, File("$report-actual.png"))
        write(pixelDiff(expected, actual), File("$report-diff.png"))
        val outcome = if (matches) "failed a Compose assertion" else "differs"
        throw AssertionError(
            "Screenshot $name $outcome on API ${Build.VERSION.SDK_INT} (ru-RU, mdpi). " +
                "Reference: $reference.png (${expected.width}x${expected.height}), " +
                "actual: ${actual.width}x${actual.height}. Inspect $report-actual.png and $report-diff.png.",
            scenarioFailure,
        )
    }

    private fun pixelDiff(expected: Bitmap, actual: Bitmap): Bitmap {
        val before = expected.readPixels()
        val after = actual.readPixels()
        val width = maxOf(expected.width, actual.width)
        val height = maxOf(expected.height, actual.height)
        val diff = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val old = if (x < expected.width && y < expected.height) before[y * expected.width + x] else null
                val new = if (x < actual.width && y < actual.height) after[y * actual.width + x] else null
                if (old != new) diff[y * width + x] = Color.MAGENTA
            }
        }
        return Bitmap.createBitmap(diff, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun Bitmap.readPixels() = IntArray(width * height).also {
        getPixels(it, 0, width, 0, 0, width, height)
    }

    private fun write(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
