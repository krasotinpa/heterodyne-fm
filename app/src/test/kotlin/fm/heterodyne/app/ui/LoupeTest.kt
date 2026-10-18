package fm.heterodyne.app.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fm.heterodyne.app.StationsScreenState
import fm.heterodyne.app.module.*
import fm.heterodyne.app.stations.*
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.stations.Band as StationBand
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1600dp-h800dp-land")
class LoupeTest {
    @get:Rule val compose = createComposeRule()
    private val stations = listOf(87500, 97700, 108000).mapIndexed { i, frequency ->
        CatalogStation("s$i", "Станция $i", StationBand.FM, frequency)
    }
    private fun show(band: Band = Band.FM, selected: (StationEntry) -> Unit = {},
                     playing: Boolean = false, play: () -> Unit = {}) {
        val catalog = Catalog(1, "test", Region("moskva", "Москва"), CatalogSource("vcfm.ru", "https://vcfm.ru"), stations)
        compose.setContent {
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, TuneTarget(band, if (band == Band.FM) 87500 else 711)),
                true, EfirActions(select = selected, play = play), playing = playing)
        }
    }
    @Test fun currentStationReleaseStartsStoppedAudioWithoutSelecting() {
        var plays = 0
        var selections = 0
        show(selected = { selections++ }, playing = false, play = { plays++ })
        val scale = compose.onNodeWithTag("band-scale")
        scale.performTouchInput { down(Offset(1f, centerY)) }
        compose.runOnIdle { assertEquals(0, plays); assertEquals(0, selections) }
        scale.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, plays); assertEquals(0, selections) }
    }
    @Test fun currentStationReleaseKeepsPlayingAudioWithoutToggling() {
        var plays = 0
        var selections = 0
        show(selected = { selections++ }, playing = true, play = { plays++ })
        compose.onNodeWithTag("band-scale").performTouchInput { down(Offset(1f, centerY)); up() }
        compose.runOnIdle { assertEquals(0, plays); assertEquals(0, selections) }
    }
    @Test fun cancelledCurrentStationDoesNotStartStoppedAudio() {
        var plays = 0
        var selections = 0
        show(selected = { selections++ }, playing = false, play = { plays++ })
        compose.onNodeWithTag("band-scale").performTouchInput {
            down(Offset(1f, centerY))
            moveTo(Offset(1f, -height.toFloat()))
            up()
        }
        compose.runOnIdle { assertEquals(0, plays); assertEquals(0, selections) }
    }
    @Test fun touchMoveAndReleaseSelectsOnlyOnce() {
        val selections = mutableListOf<StationEntry>()
        show(selected = { selections.add(it) })
        val scale = compose.onNodeWithTag("band-scale")
        scale.performTouchInput { down(center) }
        compose.onNodeWithTag("loupe").assertIsDisplayed()
        compose.onNodeWithTag("loupe-selected").assertTextContains("Станция 1", substring = true)
        assertTrue(selections.isEmpty())
        scale.performTouchInput { moveTo(Offset(width.toFloat() - 1, centerY)) }
        compose.onNodeWithTag("loupe-selected").assertTextContains("Станция 2", substring = true)
        scale.performTouchInput { up() }
        compose.onNodeWithTag("loupe").assertDoesNotExist()
        assertEquals(listOf(stations.last().copyEntry()), selections)
    }
    @Test fun verticalEscapeCancelsAndCurrentStationDoesNotSelect() {
        val selections = mutableListOf<StationEntry>()
        show(selected = { selections.add(it) })
        val scale = compose.onNodeWithTag("band-scale")
        scale.performTouchInput { down(center); moveTo(Offset(centerX, -height.toFloat())) }
        compose.onNodeWithText("Отмена — отпустите палец. Частота не изменится").assertIsDisplayed()
        scale.performTouchInput { up() }
        scale.performTouchInput { down(Offset(1f, centerY)); up() }
        assertTrue(selections.isEmpty())
    }
    @Test fun systemCancellationDoesNotSelect() {
        val selections = mutableListOf<StationEntry>()
        show(selected = { selections.add(it) })
        val scale = compose.onNodeWithTag("band-scale")
        scale.performTouchInput { down(center) }
        compose.onNodeWithTag("loupe").assertIsDisplayed()
        scale.performTouchInput { cancel() }
        compose.onNodeWithTag("loupe").assertDoesNotExist()
        assertTrue(selections.isEmpty())
    }
    @Test fun unavailableStatesRejectTouch() {
        val snapshot = mutableStateOf(TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)))
        val connected = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        val catalog = Catalog(1, "test", Region("moskva", "Москва"), CatalogSource("vcfm.ru", "https://vcfm.ru"), stations)
        var calls = 0
        compose.setContent {
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog), error = error.value),
                snapshot.value, connected.value, EfirActions(select = { calls++ }))
        }
        fun rejected() {
            compose.onNodeWithTag("band-scale").performTouchInput { down(center) }
            compose.onNodeWithTag("loupe").assertDoesNotExist()
            compose.onNodeWithTag("band-scale").performTouchInput { up() }
            assertEquals(0, calls)
        }
        rejected()
        compose.runOnIdle { connected.value = true; snapshot.value = snapshot.value.copy(state = TransitionState.Waiting) }
        rejected()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(state = TransitionState.Unconfirmed) }
        rejected()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(state = TransitionState.Idle); error.value = "Ошибка" }
        rejected()
    }
    @Test fun amHasNoLoupe() {
        show(Band.AM)
        compose.onNodeWithTag("band-scale").performTouchInput { down(center) }
        compose.onNodeWithTag("loupe").assertDoesNotExist()
        compose.onNodeWithTag("band-scale").performTouchInput { up() }
    }
}
