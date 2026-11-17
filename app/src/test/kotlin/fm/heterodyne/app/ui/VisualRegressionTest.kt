package fm.heterodyne.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import fm.heterodyne.app.StationsScreenState
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.module.TuneTarget
import fm.heterodyne.app.stations.StationsState
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lightEfirMatchesReference() = checkReference("efir-light", ThemeChoice.Light)
    @Test fun darkEfirMatchesReference() = checkReference("efir-dark", ThemeChoice.Dark)

    private fun checkReference(name: String, theme: ThemeChoice) {
        compose.setContent {
            HeterodyneTheme(theme, Palette.Coral) {
                EfirScreen(StationsScreenState(StationsState.defaults()),
                    TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 97700)),
                    connected = true, actions = EfirActions())
            }
        }
        compose.onNodeWithTag("efir-frequency").assertTextEquals("97,7")
        val actual = compose.runOnIdle {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }
        val pixels = IntArray(actual.width * actual.height)
        actual.getPixels(pixels, 0, actual.width, 0, 0, actual.width, actual.height)
        assertTrue("Capture must contain rendered UI", pixels.any { it != pixels[0] })
        if (System.getenv("HETERODYNE_RECORD_SCREENSHOTS") == "1") {
            check(System.getenv("CI") != "true") { "Recording references is disabled on CI" }
            write(actual, File("src/test/resources/screenshots/$name.png"))
            return
        }
        val stream = requireNotNull(javaClass.getResourceAsStream("/screenshots/$name.png")) {
            "Missing reference $name; record and inspect it explicitly"
        }
        val expected = stream.use(BitmapFactory::decodeStream)
        if (!expected.sameAs(actual)) {
            write(actual, File("build/reports/screenshots/$name-actual.png"))
            throw AssertionError("Screenshot $name differs; inspect build/reports/screenshots/$name-actual.png")
        }
    }

    private fun write(bitmap: Bitmap, file: File) {
        file.parentFile.mkdirs()
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
