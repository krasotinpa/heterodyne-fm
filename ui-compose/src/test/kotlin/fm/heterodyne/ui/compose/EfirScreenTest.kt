package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1600dp-h800dp-land")
class EfirScreenTest {

    @Test fun savedStationIsMutedUntilConfirmedWithoutSendingCommands() {
        val snapshot = mutableStateOf(tuning(TuningPhase.Disconnected, saved = station(90300, "Справочная")))
        val sent = mutableListOf<RadioAction>()
        var disabled = Color.Unspecified
        var normal = Color.Unspecified
        compose.setContent {
            disabled = LocalAppearanceColors.current["disabledText"]
            normal = LocalAppearanceColors.current["text"]
            EfirScreen(sampleRadio(initial, snapshot.value, connected = false), sent::add)
        }
        compose.onNodeWithTag("efir-frequency").assertTextEquals("90,3")
        compose.onNodeWithTag("efir-name").assertTextEquals("Справочная")
        listOf("efir-frequency", "efir-name").forEach { assertTextColor(it, disabled) }
        compose.runOnIdle {
            snapshot.value = tuning(confirmed = station(87500, "Первая"))
        }
        compose.onNodeWithTag("efir-frequency").assertTextEquals("87,5")
        compose.onNodeWithTag("efir-name").assertTextEquals("Первая")
        listOf("efir-frequency", "efir-name").forEach { assertTextColor(it, normal) }
        assertTrue(sent.isEmpty())
    }

    @Test fun savedFmStationKeepsConnectingReasonUntilConfirmed() {
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(saved = station(90300)), true), {})
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("Подключение идёт")
    }

    @Test fun noSavedOrConfirmedStationShowsDash() {
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Disconnected, null, null), false), {})
        }
        compose.onNodeWithTag("efir-frequency").assertTextEquals("—")
    }

    private fun assertTextColor(tag: String, expected: Color) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(expected, results.single().layoutInput.style.color)
    }

    @Test fun disconnectedReceiverDoesNotDrawPauseRing() {
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Seeking, null, station(87500),
                    pause(PauseKind.Seek, Direction.Up, 5000)), false), {})
        }
        compose.onAllNodes(hasTestTag("pause-ring-Up") or hasTestTag("pause-ring-Down")).assertCountEquals(0)
    }

    @Test fun stepRemainsAvailableDuringSeeking() {
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Seeking, null, station(87500)), true), {})
        }
        compose.onNodeWithText("Шаг ◀").assertIsEnabled()
        compose.onNodeWithText("Шаг ▶").assertIsEnabled()
    }

    @Test fun fmOnlyReceiverReservesBandAndSeekSpaceWithoutControls() {
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Idle, null, station(87500)), true, features = emptySet()), {})
        }
        compose.onNodeWithTag("seek-group").assertWidthIsEqualTo(224.dp)
        compose.onNodeWithTag("band-reserved").assertHeightIsEqualTo(DesignTokens.number("size.bandButton.height").dp)
        compose.onNodeWithContentDescription("Поиск ◀").assertDoesNotExist()
        compose.onNodeWithContentDescription("Поиск ▶").assertDoesNotExist()
        compose.onNodeWithContentDescription("FM / AM").assertDoesNotExist()
    }

    @get:Rule val compose = createComposeRule()

    @Test fun drivingSeekHintAppearsInStatusAndKeepsControlsEnabled() {
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.CommandPause, null, station(87500)), true, driving = true, seekHint = 4), {})
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("Поиск доступен через 4 с")
        compose.onNodeWithText("Шаг ▶").assertIsEnabled()
    }

    @Test fun seekingStatusWinsOverStaleSeekHint() {
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Seeking, null, station(87500)), true, driving = true, seekHint = 1), {})
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("идёт поиск…")
    }

    @Test fun seekGroupKeepsFixedWidthInParkingPauseAndDriving() {
        val driving = mutableStateOf(false)
        val state = mutableStateOf(TuningPhase.Idle)
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(state.value, null, station(87500)), true, driving = driving.value), {})
        }
        listOf(false to TuningPhase.Idle, false to TuningPhase.CommandPause,
            true to TuningPhase.CommandPause, true to TuningPhase.Idle).forEach { (moving, transition) ->
            compose.runOnIdle { driving.value = moving; state.value = transition }
            compose.onNodeWithTag("seek-group").assertWidthIsEqualTo(224.dp)
        }
    }

    @Test fun seekLabelFitsBetweenDirectionalButtonsInParkingAndPause() {
        val state = mutableStateOf(TuningPhase.Idle)
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(state.value, null, station(87500)), true), {})
        }
        listOf(TuningPhase.Idle to "Поиск", TuningPhase.CommandPause to "Поиск").forEach { (transition, text) ->
            compose.runOnIdle { state.value = transition }
            val label = compose.onNodeWithText(text).assertWidthIsEqualTo(104.dp)
            val bounds = label.fetchSemanticsNode().boundsInRoot
            val left = compose.onNodeWithContentDescription("Поиск ◀").fetchSemanticsNode().boundsInRoot
            val right = compose.onNodeWithContentDescription("Поиск ▶").fetchSemanticsNode().boundsInRoot
            assertTrue(left.right <= bounds.left && bounds.right <= right.left)
            assertEquals((left.center.x + right.center.x) / 2, bounds.center.x, .5f)
            val results = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertEquals(14f, layout.layoutInput.style.fontSize.value)
            assertEquals(TextAlign.Center, layout.layoutInput.style.textAlign)
            assertEquals(1, layout.layoutInput.maxLines)
            assertEquals(1, layout.lineCount)
            assertTrue(!layout.hasVisualOverflow)
        }
    }

    @Test fun stepPauseIsGreyWithoutRingOrPauseText() {
        val pause = pause(PauseKind.Step, Direction.Up, 500)
        var clicks = 0
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.CommandPause, null, station(87500), pause), true), { clicks++ })
        }
        listOf("Шаг ◀", "Шаг ▶").forEach { compose.onNodeWithText(it).assertIsNotEnabled().performTouchInput { click() } }
        listOf("Поиск ◀", "Поиск ▶").forEach { compose.onNodeWithContentDescription(it).assertIsEnabled() }
        compose.onAllNodesWithText("пауза", substring = true).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("pause-ring-Up") or hasTestTag("pause-ring-Down")).assertCountEquals(0)
        assertEquals(0, clicks)
    }

    @Test fun seekPauseDescriptionTracksPressedDirection() {
        compose.mainClock.autoAdvance = false
        val direction = mutableStateOf(Direction.Down)
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Idle, null, station(87500),
                    pause(PauseKind.Seek, direction.value, 5000)), true), {})
        }
        compose.onNodeWithContentDescription("Поиск ◀, пауза, осталось 5 с").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Поиск ▶").assertIsNotEnabled()
        compose.runOnIdle { direction.value = Direction.Up }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Поиск ▶, пауза, осталось 5 с").assertIsNotEnabled()
        compose.onAllNodesWithText("пауза", substring = true).assertCountEquals(0)
    }

    @Test fun disabledParkingBackgroundDiffersFromEnabledAndUsesSurface() {
        val disabledBackground = controlBackgroundToken(enabled = false)
        val enabledBackground = controlBackgroundToken(enabled = true)
        assertEquals("surface", disabledBackground)
        assertEquals("surface2", enabledBackground)
        assertTrue("Disabled background must differ from enabled", disabledBackground != enabledBackground)
    }

    @Test fun drivingParkingTextAndIconUseDisabledText() {
        var expected = Color.Unspecified
        compose.setContent {
            expected = LocalAppearanceColors.current["disabledText"]
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Idle, null, station(87500)), true, driving = true), {})
        }
        compose.onNodeWithText("Стоянка").assertIsNotEnabled()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Стоянка", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(expected, results.single().layoutInput.style.color)
        compose.onNodeWithText("≡", useUnmergedTree = true).assertDoesNotExist()
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun parkLabelFitsAtNormalAndLargeFontScale() {
        val scale = mutableStateOf(1f)
        val large = mutableStateOf(false)
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density, scale.value)) {
                Box(Modifier.requiredSize(1100.dp, 580.dp)) {
                    EfirScreen(sampleRadio(initial, tuning(TuningPhase.Idle, null, station(87500)), true, parkLargeIcon = large.value), {})
                }
            }
        }
        listOf(1f, 1.3f).forEach { value ->
            compose.runOnIdle { scale.value = value }
            listOf(false, true).forEach { big ->
                compose.runOnIdle { large.value = big }
                val label = compose.onNodeWithText("Стоянка", useUnmergedTree = true)
                val results = mutableListOf<TextLayoutResult>()
                label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val layout = results.single()
                assertEquals(1, layout.layoutInput.maxLines)
                assertTrue(!layout.layoutInput.softWrap)
                assertEquals(1, layout.lineCount)
                assertTrue("scale=$value large=$big width=${layout.size.width}", !layout.hasVisualOverflow)
                val bounds = label.fetchSemanticsNode().boundsInRoot
                val icon = compose.onNodeWithTag("park-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val button = compose.onNodeWithContentDescription("Стоянка").fetchSemanticsNode().boundsInRoot
                assertTrue(icon.right <= bounds.left && bounds.right <= button.right)
            }
        }
    }

    @Test fun parkIconOnlyKeepsSizeCenterAccessibilityAndDrivingGuard() {
        val label = mutableStateOf(true)
        val large = mutableStateOf(false)
        val driving = mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Idle, null, station(87500)), true, driving = driving.value, parkShowLabel = label.value, parkLargeIcon = large.value), { if (it == RadioAction.EnterParking) clicks++ })
        }
        compose.runOnIdle { label.value = false }
        compose.onNodeWithText("Стоянка", useUnmergedTree = true).assertDoesNotExist()
        listOf(false, true).forEach { big ->
            compose.runOnIdle { large.value = big }
            val button = compose.onNodeWithContentDescription("Стоянка")
                .assertWidthIsEqualTo(138.dp).assertHeightIsEqualTo(56.dp)
            val icon = compose.onNodeWithTag("park-icon", useUnmergedTree = true)
                .assertWidthIsEqualTo(if (big) 44.dp else 36.dp)
            assertEquals(button.fetchSemanticsNode().boundsInRoot.center,
                icon.fetchSemanticsNode().boundsInRoot.center)
        }
        compose.onNodeWithContentDescription("Стоянка").performClick()
        assertEquals(1, clicks)
        compose.runOnIdle { driving.value = true }
        compose.onNodeWithContentDescription("Стоянка").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, clicks)
        compose.runOnIdle { driving.value = false; label.value = true }
        compose.onNodeWithText("Стоянка", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun deniedAutostartShowsExactStatusAndKeepsManualStartAvailable() {
        var starts = 0
        compose.setContent {
            EfirScreen(sampleRadio(stationsView(catalog = catalog), tuning(TuningPhase.Idle, null, null), true, noFocus = true), { if (it == RadioAction.Start) starts++ })
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("Нет фокуса, нажмите Старт")
        compose.onNodeWithText("Старт").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun compactCatalogKeepsOnePlateVisibleWithAllActionMessages() {
        val screen = mutableStateOf(stationsView(catalog = catalog).copy(dataIssue = DataIssue.Recovered))
        compose.setContent {
            Box(Modifier.requiredSize(935.dp, 493.dp)) {
                EfirScreen(sampleRadio(screen.value, tuning(TuningPhase.Unconfirmed, null, null), false), {})
            }
        }
        compose.onNodeWithTag("catalog-grid").assertHeightIsAtLeast(tokenDp("size.plate.heightCompact"))
        compose.onNodeWithText("Справочная", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Проверить связь").assertHeightIsEqualTo(tokenDp("size.touch.min"))
        compose.onNodeWithText("Подключить приёмник").assertHeightIsEqualTo(tokenDp("size.touch.min"))
        val bodyHeight = compose.onNodeWithTag("efir-body").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { screen.value = screen.value.copy(loaded = false, catalog = emptyList()) }
        // Loading has no catalog yet; its extra message must not take more body space.
        assertEquals(bodyHeight, compose.onNodeWithTag("efir-body").fetchSemanticsNode().boundsInRoot.height, .5f)
    }

    @Test fun longestReasonFitsCompactStatusWithoutVisualOverflow() {
        compose.setContent {
            Box(Modifier.requiredSize(935.dp, 493.dp)) {
                EfirScreen(sampleRadio(stationsView(catalog = catalog), tuning(TuningPhase.Unresponsive, null, null), true), {})
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("efir-status").assertTextEquals("Модуль не отвечает, переподключите USB")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertTrue("Reason must be at least 17sp", layout.layoutInput.style.fontSize.value >= 17f)
        assertEquals(1, layout.lineCount)
        assertTrue("Reason must not be ellipsized", !layout.isLineEllipsized(0))
        val status = compose.onNodeWithTag("efir-status").fetchSemanticsNode().boundsInRoot
        val topBar = compose.onNodeWithTag("efir-topbar").fetchSemanticsNode().boundsInRoot
        assertTrue("Status $status must fit inside top bar $topBar",
            status.left >= topBar.left && status.top >= topBar.top &&
                status.right <= topBar.right && status.bottom <= topBar.bottom)
        assertEquals(DesignTokens.number("font.style.plate.size"), results.single().layoutInput.style.fontSize.value)
    }

    @Test fun disconnectedConnectButtonHasLargeTargetAndDoesNotCoverCatalog() {
        val many = (0 until 20).map {
            CatalogStationView("s$it", 88000 + it * 100, "Станция $it")
        }
        compose.setContent {
            Box(Modifier.requiredSize(935.dp, 493.dp)) {
                EfirScreen(sampleRadio(stationsView(catalog = many), tuning(TuningPhase.Disconnected, null, null), false), {})
            }
        }
        val button = compose.onNodeWithText("Подключить приёмник")
        button.assertHeightIsAtLeast(tokenDp("size.touch.min")).assertWidthIsAtLeast(tokenDp("size.touch.min"))
        val bounds = button.fetchSemanticsNode().boundsInRoot
        compose.onAllNodesWithText("Станция ", substring = true).fetchSemanticsNodes()
            .forEach { assertTrue("Connect overlaps catalog plate", !bounds.overlaps(it.boundsInRoot)) }
        assertTrue(!bounds.overlaps(compose.onNodeWithTag("catalog-grid").fetchSemanticsNode().boundsInRoot))
    }

    @Test fun connectionAndEmptySelectionReasonsUseTokenStyleWithinUnchangedTopBar() {
        val connection = mutableStateOf(Connection.Disconnected)
        compose.setContent {
            EfirScreen(sampleRadio(stationsView(catalog = catalog), tuning(TuningPhase.Idle, null, station(87500)), connection.value == Connection.Observing, connectionState = connection.value), {})
        }
        listOf(Connection.Disconnected, Connection.PermissionPending, Connection.DeviceFound,
            Connection.Observing).forEach { state ->
            compose.runOnIdle { connection.value = state }
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("efir-status").performSemanticsAction(
                SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(DesignTokens.number("font.style.plate.size"), results.single().layoutInput.style.fontSize.value)
            compose.onNodeWithTag("efir-topbar").assertHeightIsEqualTo(tokenDp("size.topBar.height"))
            compose.onNodeWithTag("rds-reserved").assertHeightIsEqualTo(tokenDp("size.rds.height"))
        }
    }

    @Test fun emptySelectionKeepsCatalogAvailableAndExplainsDisabledNavigation() {
        compose.setContent {
            EfirScreen(sampleRadio(stationsView(catalog = catalog), tuning(TuningPhase.Idle, null, station(87500)), true), {})
        }
        compose.onAllNodesWithText("Подборка пуста — добавьте станции в режиме стоянки").assertCountEquals(1)
        compose.onNodeWithText("Подборка пуста — добавьте станции в режиме стоянки")
            .assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("Подборка пуста — добавьте станции в режиме стоянки")
            .onParent().onChildren().assertCountEquals(1)
        compose.onNodeWithText("Станция ◀").assertIsNotEnabled()
        compose.onNodeWithText("Станция ▶").assertIsNotEnabled()
        compose.onNodeWithText("Справочная", substring = true).assertIsEnabled()
        compose.onNodeWithTag("rds-reserved").assertHeightIsEqualTo(tokenDp("size.rds.height"))
        compose.onNodeWithTag("spectrum-reserved").assertHeightIsEqualTo(56.dp)
    }

    @Test fun seekingWithEmptySelectionShowsSearchInsteadOfEmptySelectionReason() {
        compose.setContent {
            EfirScreen(sampleRadio(stationsView(catalog = catalog), tuning(TuningPhase.Seeking, null, station(87500)), true), {})
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("идёт поиск…").assertIsDisplayed()
        compose.onNodeWithText("Станция ◀").assertIsNotEnabled()
        compose.onNodeWithText("Поиск", substring = false).assertIsDisplayed()
    }

    @Test fun expiredSeekShowsIncompleteAndKeepsCommandsAvailable() {
        compose.setContent {
            EfirScreen(sampleRadio(stationsView(catalog = catalog), tuning(TuningPhase.SeekIncomplete, null, station(87500)), true), {})
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("Поиск не завершён").assertIsDisplayed()
        compose.onNodeWithText("идёт поиск…").assertDoesNotExist()
        compose.onNodeWithText("Шаг ◀").assertIsEnabled()
        compose.onNodeWithText("Справочная", substring = true).assertIsEnabled()
    }

    @Test fun nonEmptyActiveSelectionDoesNotShowEmptySelectionLabel() {
        val state = initial
        compose.setContent {
            EfirScreen(sampleRadio(state, tuning(TuningPhase.Idle, null, station(87500)), true), {})
        }
        compose.onNodeWithText("Подборка пуста — добавьте станции в режиме стоянки").assertDoesNotExist()
    }

    @Test fun referenceLayoutAndCompactBreakpointKeepGridScrollable() {
        val window = mutableStateOf(1100.dp to 580.dp)
        val many = (0 until 20).map {
            CatalogStationView("s$it", 88000 + it * 100, "Станция $it")
        }
        compose.setContent {
            Box(Modifier.requiredSize(window.value.first, window.value.second)) {
                EfirScreen(sampleRadio(stationsView(catalog = many), tuning(TuningPhase.Idle, null, station(87500)), true), {})
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertEquals(88f, bounds("efir-topbar").height / density, .5f)
        assertEquals(852f, bounds("efir-center").width / density, .5f)
        assertEquals(448f, bounds("efir-center").height / density, .5f)
        compose.onNodeWithTag("band-scale").assertHeightIsEqualTo(84.dp)
        val first = compose.onNodeWithText("Станция 0").fetchSemanticsNode().boundsInRoot
        val secondRow = compose.onNodeWithText("Станция 5").fetchSemanticsNode().boundsInRoot
        assertEquals(164f, first.width / density, 1f)
        assertEquals(64f, first.height / density, 1f)
        assertEquals(72f, (secondRow.top - first.top) / density, 1f)
        compose.onNodeWithTag("catalog-grid").performScrollToIndex(19)
        compose.onNodeWithText("Станция 19").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { window.value = 935.dp to 493.dp }
        compose.onNodeWithTag("spectrum-reserved").assertHeightIsEqualTo(32.dp)
        compose.onNodeWithText("Станция ◀").assertHeightIsEqualTo(153.dp)
        assertEquals(687f, bounds("efir-center").width / density, 1f)
        compose.runOnIdle { window.value = 1265.dp to 667.dp }
        compose.onNodeWithTag("spectrum-reserved").assertHeightIsEqualTo(56.dp)
        assertTrue(bounds("catalog-grid").height / density >= 208f)
    }

    @Test fun pressFeedbackAppearsWhileHeldAndReleasesWithoutDispatchOnCancel() {
        var clicks = 0
        compose.setContent {
            Control("Нажать", true, { clicks++ }, Modifier.requiredSize(160.dp, 64.dp))
        }
        val button = compose.onNodeWithText("Нажать")
        val before = button.fetchSemanticsNode().boundsInRoot
        button.performTouchInput { down(center) }
        compose.waitForIdle()
        val held = button.fetchSemanticsNode().boundsInRoot
        assertEquals(before.width * .98f, held.width, 1f)
        assertEquals(0, clicks)
        button.performTouchInput { cancel() }
        compose.waitForIdle()
        assertEquals(before.width, button.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(0, clicks)
    }

    @Test fun drivingDisablesParking() {
        compose.setContent {
            EfirScreen(sampleRadio(stationsView().copy(loaded = false), tuning(TuningPhase.Disconnected, null, null), false, driving = true), {})
        }
        compose.onNodeWithText("Стоянка").assertIsNotEnabled()
    }

    @Test fun drivingHidesSeekWithoutMovingNeighborsAndParkingRestoresActions() {
        val driving = mutableStateOf(false)
        val transition = mutableStateOf(TuningPhase.Idle)
        val seeks = mutableListOf<Direction>()
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(transition.value, null, station(87500, "Первая")), true, driving = driving.value), { if (it is RadioAction.Seek) seeks.add(it.direction) })
        }
        fun neighborBounds() = listOf(
            compose.onAllNodesWithText("87,5").onFirst(),
            compose.onNodeWithText("Первая"),
            compose.onNodeWithTag("band-reserved"),
            compose.onNodeWithText("Стоянка"),
        ).map { it.fetchSemanticsNode().boundsInRoot }
        val parkedBounds = neighborBounds()
        compose.onNodeWithText("Поиск").assertIsDisplayed()
        compose.runOnIdle { driving.value = true }
        // Merged tree is what accessibility sees; the hidden group stays composed only to keep its width.
        compose.onNodeWithContentDescription("Поиск ◀").assertDoesNotExist()
        compose.onNodeWithContentDescription("Поиск ▶").assertDoesNotExist()
        compose.onNodeWithText("Поиск").assertDoesNotExist()
        assertEquals(parkedBounds, neighborBounds())
        compose.onNodeWithTag("seek-group").performTouchInput {
            val inset = tokenDp("size.seekButton.width").toPx() / 2
            click(Offset(inset, center.y))
            click(Offset(width - inset, center.y))
        }
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        compose.runOnIdle { driving.value = false }
        compose.onNodeWithContentDescription("Поиск ◀").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Поиск ▶").assertIsEnabled().performClick()
        assertEquals(listOf(Direction.Down, Direction.Up), seeks)
        compose.runOnIdle { transition.value = TuningPhase.CommandPause }
        compose.onNodeWithText("Поиск").assertIsDisplayed()
        assertEquals(parkedBounds, neighborBounds())
        compose.runOnIdle { driving.value = true }
        compose.onNodeWithText("Поиск").assertDoesNotExist()
        assertEquals(parkedBounds, neighborBounds())
    }

    @Test fun drivingKeepsOngoingSeekStatus() {
        val driving = mutableStateOf(false)
        compose.setContent {
            EfirScreen(sampleRadio(stationsView(catalog = catalog), tuning(TuningPhase.Seeking, null, station(87500)), true, driving = driving.value), {})
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("идёт поиск…")
        compose.runOnIdle { driving.value = true }
        compose.onNodeWithTag("efir-status").assertTextEquals("идёт поиск…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Поиск ◀").assertDoesNotExist()
        compose.onNodeWithContentDescription("Поиск ▶").assertDoesNotExist()
    }

    @Test fun drivingKeepsDisabledReasonWhenSeekIsHidden() {
        val connection = mutableStateOf(Connection.Disconnected)
        compose.setContent {
            EfirScreen(sampleRadio(stationsView(catalog = catalog), tuning(TuningPhase.Idle, null, station(87500)), connection.value == Connection.Observing, driving = true, connectionState = connection.value), {})
        }
        listOf(
            Connection.Disconnected to "Приёмник отключён",
            Connection.PermissionPending to "Нужно разрешение USB",
            Connection.DeviceFound to "Подключение идёт",
            Connection.Observing to "Подборка пуста",
        ).forEach { (state, reason) ->
            compose.runOnIdle { connection.value = state }
            compose.onNodeWithTag("efir-status").assertTextEquals(reason).assertIsDisplayed()
            compose.onNodeWithContentDescription("Поиск ◀").assertDoesNotExist()
            compose.onNodeWithContentDescription("Поиск ▶").assertDoesNotExist()
        }
    }

    @Test fun catalogStationsSharingFrequencyDispatchTheirOwnIds() {
        val stations = stationsView(catalog = listOf(CatalogStationView("a", 90300, "Первая запись"),
            CatalogStationView("b", 90300, "Вторая запись")))
        val actions = mutableListOf<RadioAction>()
        compose.setContent { EfirScreen(sampleRadio(stations), actions::add) }
        listOf("Первая запись", "Вторая запись").forEach { compose.onNodeWithText(it, substring = true).performClick() }
        assertEquals(listOf(RadioAction.SelectStation("moskva", StationRef.Catalog("a")),
            RadioAction.SelectStation("moskva", StationRef.Catalog("b"))), actions)
    }

    @Test fun catalogSelectionRequestsStationWithoutChangingConfirmedFrequency() {
        val selected = mutableListOf<RadioAction>()
        val catalog = listOf(CatalogStationView("a", 90300, "Авторадио"))
        compose.setContent {
            EfirScreen(sampleRadio(stationsView(catalog = catalog), tuning(TuningPhase.Idle, null, station(87500)), connected = true), selected::add)
        }
        compose.onNodeWithText("Авторадио", substring = true).performClick()
        assertEquals(listOf(RadioAction.SelectStation("moskva", StationRef.Catalog("a"))), selected)
        compose.onAllNodesWithText("87,5").onFirst().assertIsDisplayed()
    }

    @Test fun statusLineShowsHighestReasonThenRegionWithoutDuplicatingBottomText() {
        val connection = mutableStateOf(Connection.Disconnected)
        val screen = mutableStateOf(stationsView(catalog = catalog))
        compose.setContent {
            EfirScreen(sampleRadio(screen.value, tuning(TuningPhase.Idle, null, station(87500)), connection.value == Connection.Observing, connectionState = connection.value), {})
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("Приёмник отключён").assertIsDisplayed()
        compose.onAllNodesWithText("Приёмник отключён").assertCountEquals(1)
        // Table: command buttons and both types of plate share the visible receiver reason.
        listOf("Старт", "Станция ◀", "Станция ▶", "Шаг ◀", "Шаг ▶").forEach {
            compose.onNodeWithText(it).assertIsNotEnabled()
        }
        listOf("Поиск ◀", "Поиск ▶").forEach {
            compose.onNodeWithContentDescription(it).assertIsNotEnabled()
        }
        compose.onNodeWithText("Справочная", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Подключить приёмник").assertIsEnabled()
        listOf(
            Connection.PermissionPending to "Нужно разрешение USB",
            Connection.DeviceFound to "Подключение идёт",
            Connection.Open to "Подключение идёт",
        ).forEach { (state, text) ->
            compose.runOnIdle { connection.value = state }
            compose.onNodeWithTag("efir-status").assertTextEquals(text).assertIsDisplayed()
            compose.onAllNodesWithText(text).assertCountEquals(1)
            compose.onNodeWithText("Старт").assertIsNotEnabled()
        }
        compose.runOnIdle { connection.value = Connection.Observing }
        compose.onNodeWithTag("efir-status").assertTextEquals("Подборка пуста")
        compose.onNodeWithText("Станция ◀").assertIsNotEnabled()
        compose.onNodeWithText("Станция ▶").assertIsNotEnabled()
        listOf("Старт", "Шаг ◀", "Шаг ▶").forEach { compose.onNodeWithText(it).assertIsEnabled() }
        compose.onNodeWithText("Справочная", substring = true).assertIsEnabled()
        compose.runOnIdle { screen.value = initial }
        compose.onNodeWithTag("efir-status").assertTextEquals("Москва")
        compose.runOnIdle { connection.value = Connection.Disconnected }
        compose.onNodeWithText("1 · Первая", substring = true).assertIsNotEnabled()
    }

    private val catalog = listOf(CatalogStationView("b", 90300, "Справочная"))
    private val initial = stationsView(entries = listOf(station(87500, "Первая")), catalog = catalog)
        .let { it.copy(selections = it.selections + SelectionView("Дорога", emptyList())) }

    @Test fun driverControlsDispatchBothDirectionsAndOpenParking() {
        val events = mutableListOf<RadioAction>()
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Idle, null, station(87500)), true), events::add)
        }
        listOf("Станция ◀", "Станция ▶", "Шаг ◀", "Шаг ▶", "Поиск ◀", "Поиск ▶",
            "Подборка: Дорога", "Старт", "Стоянка").forEach {
            if (it == "Подборка: Дорога") compose.onNodeWithText("Подборка: Основной").performClick()
            val node = if (it.startsWith("Поиск")) compose.onNodeWithContentDescription(it) else compose.onNodeWithText(it)
            node.assertWidthIsAtLeast(tokenDp("size.touch.min")).assertHeightIsAtLeast(tokenDp("size.touch.min")).performClick()
        }
        assertEquals(listOf(RadioAction.MoveInSelection(Direction.Down), RadioAction.MoveInSelection(Direction.Up),
            RadioAction.Step(Direction.Down), RadioAction.Step(Direction.Up), RadioAction.Seek(Direction.Down),
            RadioAction.Seek(Direction.Up), RadioAction.ActivateSelection("moskva", "Дорога"), RadioAction.Start, RadioAction.EnterParking), events)
        compose.onNodeWithText("Сохранить текущую частоту").assertDoesNotExist()
        compose.onNodeWithText("Отменить поиск").assertDoesNotExist()
    }

    @Test fun disconnectedModuleDisablesStationCardsAndCommands() {
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Disconnected, null, station(87500)), false), {})
        }
        compose.onNodeWithText("Приёмник отключён").assertIsDisplayed()
        listOf("Станция ▶", "Шаг ◀", "Поиск ▶", "Старт").forEach {
            val node = if (it.startsWith("Поиск")) compose.onNodeWithContentDescription(it) else compose.onNodeWithText(it)
            node.assertIsNotEnabled()
        }
        compose.onNodeWithText("Справочная", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Подборка: Основной").assertIsEnabled()
        compose.onNodeWithText("Стоянка").assertIsEnabled()
    }

    @Test fun pendingTargetStaysSelectedWhileIdleDuringCommandLimit() {
        compose.setContent {
            EfirScreen(sampleRadio(initial, tuning(TuningPhase.Idle, 90300, station(87500)), true), {})
        }
        compose.onNodeWithText("Справочная", substring = true).assertIsSelected()
    }

    @Test fun transitionBannersFollowControllerSnapshotAndKeepLastConfirmedFrequency() {
        val snapshot = mutableStateOf(tuning(TuningPhase.Waiting,
            90300, station(87500)))
        var checks = 0
        compose.setContent {
            EfirScreen(sampleRadio(initial, snapshot.value, true), { if (it == RadioAction.CheckConnection) checks++ })
        }
        compose.onNodeWithText("ожидание").assertIsDisplayed()
        compose.onNodeWithText("Справочная", substring = true).assertIsSelected()
        compose.onAllNodesWithText("87,5").onFirst().assertIsDisplayed()
        listOf(
            TuningPhase.Unconfirmed to "Нет ответа от приёмника",
            TuningPhase.Unresponsive to "Модуль не отвечает, переподключите USB",
            TuningPhase.CommandPause to "Москва",
            TuningPhase.Seeking to "идёт поиск…",
        ).forEach { (state, banner) ->
            compose.runOnIdle { snapshot.value = snapshot.value.copy(phase = state, targetKhz = null) }
            compose.onNodeWithText(banner).assertIsDisplayed()
            if (state == TuningPhase.Unconfirmed) compose.onNodeWithText("Проверить связь").performClick()
        }
        assertEquals(1, checks)
        compose.runOnIdle { snapshot.value = snapshot.value.copy(phase = TuningPhase.Idle) }
        compose.onNodeWithText("идёт поиск…").assertDoesNotExist()
    }
}
