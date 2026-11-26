package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
        CatalogStationView("s$i", frequency, "Станция $i")
    }
    private fun show(confirmed: StationView? = station(87500), selected: (StationRef) -> Unit = {},
                     playing: Boolean = false, play: () -> Unit = {}) {
        compose.setContent {
            EfirScreen(sampleRadio(stationsView(catalog = stations), tuning(confirmed = confirmed),
                playing = playing)) { action ->
                when (action) {
                    is RadioAction.SelectStation -> selected(action.ref)
                    RadioAction.Start -> play()
                    else -> Unit
                }
            }
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
        val selections = mutableListOf<StationRef>()
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
        assertEquals(listOf(StationRef.Catalog(stations.last().id)), selections)
    }
    @Test fun verticalEscapeCancelsAndCurrentStationDoesNotSelect() {
        val selections = mutableListOf<StationRef>()
        show(selected = { selections.add(it) })
        val scale = compose.onNodeWithTag("band-scale")
        scale.performTouchInput { down(center); moveTo(Offset(centerX, -height.toFloat())) }
        compose.onNodeWithText("Отмена — отпустите палец. Частота не изменится").assertIsDisplayed()
        scale.performTouchInput { up() }
        scale.performTouchInput { down(Offset(1f, centerY)); up() }
        assertTrue(selections.isEmpty())
    }
    @Test fun systemCancellationDoesNotSelect() {
        val selections = mutableListOf<StationRef>()
        show(selected = { selections.add(it) })
        val scale = compose.onNodeWithTag("band-scale")
        scale.performTouchInput { down(center) }
        compose.onNodeWithTag("loupe").assertIsDisplayed()
        scale.performTouchInput { cancel() }
        compose.onNodeWithTag("loupe").assertDoesNotExist()
        assertTrue(selections.isEmpty())
    }
    @Test fun unavailableStatesRejectTouch() {
        val snapshot = mutableStateOf(tuning(TuningPhase.Idle, null, station(87500)))
        val connected = mutableStateOf(false)
        val error = mutableStateOf<DataIssue?>(null)
        var calls = 0
        compose.setContent {
            EfirScreen(sampleRadio(stationsView(catalog = stations).copy(dataIssue = error.value),
                snapshot.value, connected.value)) { calls++ }
        }
        fun rejected() {
            compose.onNodeWithTag("band-scale").performTouchInput { down(center) }
            compose.onNodeWithTag("loupe").assertDoesNotExist()
            compose.onNodeWithTag("band-scale").performTouchInput { up() }
            assertEquals(0, calls)
        }
        rejected()
        compose.runOnIdle { connected.value = true; snapshot.value = snapshot.value.copy(phase = TuningPhase.Waiting) }
        rejected()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(phase = TuningPhase.Unconfirmed) }
        rejected()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(phase = TuningPhase.Idle); error.value = DataIssue.LoadFailed }
        rejected()
    }
    @Test fun missingConfirmedFmHasNoLoupe() {
        show(confirmed = null)
        compose.onNodeWithTag("band-scale").performTouchInput { down(center) }
        compose.onNodeWithTag("loupe").assertDoesNotExist()
        compose.onNodeWithTag("band-scale").performTouchInput { up() }
    }
}

/** R21 includes the composited, dimmed neighbours, not just the raw text token. */
class LoupeContrastTest {
    @Test fun textMeetsR21InEveryThemeAndPaletteIncludingDimmedNeighbours() {
        Palette.entries.forEach { palette -> ResolvedTheme.entries.forEach { theme ->
            fun rgb(name: String) = DesignTokens.color(palette, theme, name).drop(1)
                .chunked(2).map { it.toInt(16) / 255.0 }
            fun luminance(rgb: List<Double>) = rgb.map {
                if (it <= .04045) it / 12.92 else Math.pow((it + .055) / 1.055, 2.4)
            }.let { it[0] * .2126 + it[1] * .7152 + it[2] * .0722 }
            fun contrast(fg: List<Double>, bg: List<Double>): Double {
                val a = luminance(fg); val b = luminance(bg)
                return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
            }
            assertTrue("$palette/$theme selected", contrast(rgb("onAccent"), rgb("accent")) >= 4.5)
            val alpha = DesignTokens.number("size.loupe.neighbourAlpha").toDouble()
            val surface = rgb("surface")
            val dimmed = rgb("text").mapIndexed { i, value -> value * alpha + surface[i] * (1 - alpha) }
            assertTrue("$palette/$theme neighbours", contrast(dimmed, surface) >= 4.5)
        } }
    }
}
