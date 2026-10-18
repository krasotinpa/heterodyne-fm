package fm.heterodyne.app.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fm.heterodyne.app.StationsScreenState
import fm.heterodyne.app.module.*
import fm.heterodyne.app.stations.Catalog
import fm.heterodyne.app.stations.CatalogStation
import fm.heterodyne.app.stations.StationsState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1600dp-h800dp-land")
class BandScaleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun efirShows53StationsAndMarkerOnlyFollowsConfirmation() {
        val source = Catalog.parse(File("src/main/assets/catalogs/moskva.json").readText())
        val catalog = source.copy(stations = source.stations + CatalogStation("test", "Тестовая",
            fm.heterodyne.app.stations.Band.FM, 108000))
        val snapshot = mutableStateOf(TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 97750)))
        compose.setContent {
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)), snapshot.value, true, EfirActions())
        }
        val scale = compose.onNodeWithTag("band-scale").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        fun marker() = compose.onNodeWithTag("band-scale-marker").fetchSemanticsNode().boundsInRoot.center.x
        val middle = marker()
        assertEquals(scale.center.x, middle, 1f)
        compose.runOnIdle { snapshot.value = snapshot.value.copy(state = TransitionState.Waiting, target = TuneTarget(Band.FM, 108000)) }
        assertEquals(middle, marker(), .1f)
        compose.runOnIdle { snapshot.value = snapshot.value.copy(confirmed = TuneTarget(Band.FM, 108000)) }
        val density = compose.density
        assertEquals(scale.right - with(density) { tokenDp("size.scale.inset").toPx() }, marker(), 1f)
        compose.runOnIdle { snapshot.value = snapshot.value.copy(confirmed = null) }
        compose.onNodeWithTag("band-scale-marker").assertDoesNotExist()
    }
}
