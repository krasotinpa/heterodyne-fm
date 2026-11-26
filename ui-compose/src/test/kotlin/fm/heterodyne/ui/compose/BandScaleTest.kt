package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

    @Test fun savedCursorMovesToConfirmedFrequencyForEveryShape() {
        val shape = mutableStateOf(ScaleMarker.Pin)
        val snapshot = mutableStateOf(tuning(TuningPhase.Disconnected, null, null))
        compose.setContent {
            BandScale(emptyList(), emptyList(), snapshot.value.copy(saved = station(87500)), marker = shape.value)
        }
        fun assertCursor(frequency: String, fraction: Float) {
            val node = compose.onNodeWithTag("band-scale-marker").assertIsDisplayed()
            node.assertContentDescriptionContains(frequency, substring = true)
            val scale = compose.onNodeWithTag("band-scale").fetchSemanticsNode().boundsInRoot
            val inset = with(compose.density) { tokenDp("size.scale.inset").toPx() }
            assertEquals(scale.left + inset + fraction * (scale.width - 2 * inset),
                node.fetchSemanticsNode().boundsInRoot.center.x, 1f)
        }
        ScaleMarker.entries.forEach { marker ->
            compose.runOnIdle {
                shape.value = marker
                snapshot.value = tuning(TuningPhase.Disconnected, null, null)
            }
            assertCursor("87,5", 0f)
            compose.runOnIdle {
                snapshot.value = tuning(TuningPhase.Idle, null, station(108000))
            }
            assertCursor("108,0", 1f)
        }
    }

    @Test fun targetBeforeFirstConfirmationUsesOutlineWithoutConfirmedMarker() {
        compose.setContent {
            BandScale(emptyList(), emptyList(), tuning(
                TuningPhase.Waiting, 97700, null, saved = station(90300)))
        }
        compose.onNodeWithTag("scale-marker-Pin-outline").assertIsDisplayed()
        compose.onNodeWithTag("band-scale-marker").assertDoesNotExist()
    }

    @Test fun stripeShowsPendingTargetBeforeAndAfterFirstConfirmation() {
        val snap = mutableStateOf(tuning(TuningPhase.Waiting, targetKhz = 101200))
        compose.setContent { BandScale(emptyList(), emptyList(), snap.value, marker = ScaleMarker.Stripe) }
        compose.onNodeWithTag("band-scale-preview-target").assertContentDescriptionContains("101,2", substring = true)
        compose.onNodeWithTag("band-scale-marker").assertDoesNotExist()
        compose.runOnIdle { snap.value = snap.value.copy(confirmed = station(90300)) }
        compose.onNodeWithTag("band-scale-confirmed-stripe").assertContentDescriptionContains("90,3", substring = true)
        compose.onNodeWithTag("band-scale-preview-target").assertContentDescriptionContains("101,2", substring = true)
    }

    @Test fun shapesApplyImmediatelyAndTransitionTargetIsOutlinedUntilConfirmed() {
        val marker = mutableStateOf(ScaleMarker.Pin)
        val snapshot = mutableStateOf(tuning(TuningPhase.Idle, null, station(97700)))
        compose.setContent { BandScale(emptyList(), emptyList(), snapshot.value, marker = marker.value) }
        ScaleMarker.entries.filter { it != ScaleMarker.Stripe }.forEach { shape ->
            compose.runOnIdle {
                marker.value = shape
                snapshot.value = tuning(TuningPhase.Idle, null, station(97700))
            }
            compose.onNodeWithTag("scale-marker-${shape.name}-filled").assertIsDisplayed()
            compose.runOnIdle { snapshot.value = snapshot.value.copy(phase = TuningPhase.Waiting, targetKhz = 100000) }
            compose.onNodeWithTag("scale-marker-${shape.name}-outline").assertIsDisplayed()
            compose.onNodeWithTag("scale-marker-${shape.name}-filled").assertIsDisplayed()
            compose.runOnIdle { snapshot.value = tuning(TuningPhase.Idle, null, station(100000)) }
            compose.onNodeWithTag("scale-marker-${shape.name}-outline").assertDoesNotExist()
        }
        compose.runOnIdle { marker.value = ScaleMarker.Stripe }
        compose.onNodeWithTag("band-scale-confirmed-stripe").assertIsDisplayed()
    }

    @Test fun confirmedStripeAndLoupeTargetHaveDistinctGeometry() {
        val stations = (0 until 52).map { CatalogStationView("id-$it", 87500 + 300 * it, "Станция $it") }
        val snapshot = mutableStateOf(tuning(TuningPhase.Idle, null, station(97700)))
        compose.setContent {
            BandScale(stations, emptyList(), snapshot.value, loupeEnabled = true, marker = ScaleMarker.Stripe)
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
        compose.runOnIdle { snapshot.value = snapshot.value.copy(confirmed = null) }
        compose.onNodeWithTag("band-scale-confirmed-stripe").assertDoesNotExist()
    }

    @Test fun efirShows53StationsAndMarkerOnlyFollowsConfirmation() {
        val stations = (0 until 52).map { CatalogStationView("id-$it", 87500 + 300 * it, "Станция $it") }
        val catalog = stations + CatalogStationView("test", 108000, "Тестовая")
        val snapshot = mutableStateOf(tuning(TuningPhase.Idle, null, station(97750)))
        compose.setContent {
            EfirScreen(sampleRadio(stations = stationsView(catalog = catalog), tuning = snapshot.value)) {}
        }
        val scale = compose.onNodeWithTag("band-scale").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        fun marker() = compose.onNodeWithTag("band-scale-marker").fetchSemanticsNode().boundsInRoot.center.x
        val middle = marker()
        assertEquals(scale.center.x, middle, 1f)
        compose.runOnIdle { snapshot.value = snapshot.value.copy(phase = TuningPhase.Waiting, targetKhz = 108000) }
        assertEquals(middle, marker(), .1f)
        compose.runOnIdle { snapshot.value = snapshot.value.copy(confirmed = station(108000)) }
        val density = compose.density
        assertEquals(scale.right - with(density) { tokenDp("size.scale.inset").toPx() }, marker(), 1f)
        compose.runOnIdle { snapshot.value = snapshot.value.copy(confirmed = null) }
        compose.onNodeWithTag("band-scale-marker").assertDoesNotExist()
    }
}
