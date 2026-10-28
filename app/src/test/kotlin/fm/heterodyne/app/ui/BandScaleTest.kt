package fm.heterodyne.app.ui

import android.app.Application
import androidx.compose.ui.graphics.toPixelMap
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

    @Test fun savedCursorMovesAndRegainsColorAfterConfirmationForEveryShape() {
        val shape = mutableStateOf(ScaleMarker.Pin)
        val snapshot = mutableStateOf(TransitionSnapshot(TransitionState.Disconnected, null, null))
        val saved = StationsState.defaults().copy(lastStation = fm.heterodyne.app.stations.SessionStation(87500))
        compose.setContent {
            EfirScreen(StationsScreenState(saved, emptyList()), snapshot.value, true, EfirActions(),
                scaleMarker = shape.value)
        }
        fun assertCursor(frequency: String, fraction: Float, faded: Boolean) {
            val node = compose.onNodeWithTag("band-scale-marker").assertIsDisplayed()
            node.assertContentDescriptionContains(frequency, substring = true)
            val scale = compose.onNodeWithTag("band-scale").fetchSemanticsNode().boundsInRoot
            val inset = with(compose.density) { tokenDp("size.scale.inset").toPx() }
            assertEquals(scale.left + inset + fraction * (scale.width - 2 * inset),
                node.fetchSemanticsNode().boundsInRoot.center.x, 1f)
            val pixels = node.captureToImage().toPixelMap()
            val ink = AppearanceColors(Palette.Coral, ResolvedTheme.Dark)[if (faded) "disabledText" else "accent"]
            assertTrue((0 until pixels.width).any { x ->
                (0 until pixels.height).any { y -> pixels[x, y] == ink }
            })
        }
        ScaleMarker.entries.forEach { marker ->
            compose.runOnIdle {
                shape.value = marker
                snapshot.value = TransitionSnapshot(TransitionState.Disconnected, null, null)
            }
            assertCursor("87,5", 0f, true)
            compose.runOnIdle {
                snapshot.value = TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 108000))
            }
            assertCursor("108,0", 1f, false)
        }
    }

    @Test fun targetBeforeFirstConfirmationUsesOutlineWithoutConfirmedMarker() {
        compose.setContent {
            BandScale(emptyList(), emptyList(), TransitionSnapshot(
                TransitionState.Waiting, TuneTarget(Band.FM, 97700), null),
                saved = TuneTarget(Band.FM, 90300))
        }
        compose.onNodeWithTag("scale-marker-Pin-outline").assertIsDisplayed()
        compose.onNodeWithTag("band-scale-marker").assertDoesNotExist()
    }

    @Test fun shapesApplyImmediatelyAndTransitionTargetIsOutlinedUntilConfirmed() {
        val marker = mutableStateOf(ScaleMarker.Pin)
        val snapshot = mutableStateOf(TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 97700)))
        compose.setContent { BandScale(emptyList(), emptyList(), snapshot.value, marker = marker.value) }
        ScaleMarker.entries.filter { it != ScaleMarker.Stripe }.forEach { shape ->
            compose.runOnIdle {
                marker.value = shape
                snapshot.value = TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 97700))
            }
            compose.onNodeWithTag("scale-marker-${shape.name}-filled").assertIsDisplayed()
            compose.runOnIdle { snapshot.value = snapshot.value.copy(state = TransitionState.Waiting, target = TuneTarget(Band.FM, 100000)) }
            compose.onNodeWithTag("scale-marker-${shape.name}-outline").assertIsDisplayed()
            compose.onNodeWithTag("scale-marker-${shape.name}-filled").assertIsDisplayed()
            compose.runOnIdle { snapshot.value = TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 100000)) }
            compose.onNodeWithTag("scale-marker-${shape.name}-outline").assertDoesNotExist()
        }
        compose.runOnIdle { marker.value = ScaleMarker.Stripe }
        compose.onNodeWithTag("band-scale-confirmed-stripe").assertIsDisplayed()
    }

    @Test fun confirmedStripeAndLoupeTargetHaveDistinctGeometry() {
        val source = Catalog.parse(File("src/main/assets/catalogs/moskva.json").readText())
        val snapshot = mutableStateOf(TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 97700)))
        compose.setContent {
            BandScale(source.stations, emptyList(), snapshot.value, loupeEnabled = true, marker = ScaleMarker.Stripe)
        }
        val stripe = compose.onNodeWithTag("band-scale-confirmed-stripe").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val slider = compose.onNodeWithTag("band-scale-marker").fetchSemanticsNode().boundsInRoot
        assertTrue(stripe.width > slider.width)
        assertTrue(stripe.height > with(compose.density) { tokenDp("size.scale.markPreset.1").toPx() })
        compose.onNodeWithTag("band-scale-preview-target").assertDoesNotExist()
        val scale = compose.onNodeWithTag("band-scale")
        scale.performTouchInput { down(androidx.compose.ui.geometry.Offset(width.toFloat() - 1, centerY)) }
        val target = compose.onNodeWithTag("band-scale-preview-target").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(target.height > stripe.height)
        assertEquals(stripe, compose.onNodeWithTag("band-scale-confirmed-stripe").fetchSemanticsNode().boundsInRoot)
        scale.performTouchInput { up() }
        compose.onNodeWithTag("band-scale-preview-target").assertDoesNotExist()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(confirmed = TuneTarget(Band.AM, 855)) }
        compose.onNodeWithTag("band-scale-confirmed-stripe").assertDoesNotExist()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(confirmed = null) }
        compose.onNodeWithTag("band-scale-confirmed-stripe").assertDoesNotExist()
    }

    @Test fun efirShows53StationsAndMarkerOnlyFollowsConfirmation() {
        val source = Catalog.parse(File("src/main/assets/catalogs/moskva.json").readText())
        val catalog = source.copy(stations = source.stations + CatalogStation("test", "Тестовая",
            108000))
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
