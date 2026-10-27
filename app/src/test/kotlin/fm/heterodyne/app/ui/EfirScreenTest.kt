package fm.heterodyne.app.ui

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
import fm.heterodyne.app.StationsScreenState
import fm.heterodyne.app.module.*
import fm.heterodyne.app.stations.*
import fm.heterodyne.app.stations.Band as StationBand
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
    @get:Rule val compose = createComposeRule()

    @Test fun drivingSeekHintAppearsInStatusAndKeepsControlsEnabled() {
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(TransitionState.CommandPause, null, TuneTarget(Band.FM, 87500)),
                true, EfirActions(), driving = true, seekHint = "Поиск доступен через 4 с")
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("Поиск доступен через 4 с")
        compose.onNodeWithText("Шаг ▶ · пауза").assertIsEnabled()
    }

    @Test fun seekingStatusWinsOverStaleSeekHint() {
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(TransitionState.Seeking, null, TuneTarget(Band.FM, 87500)),
                true, EfirActions(), driving = true, seekHint = "Поиск доступен через 1 с")
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("идёт поиск…")
    }

    @Test fun seekHintRoundsUpOnlyWhileDriving() {
        assertEquals("Поиск доступен через 4 с", fm.heterodyne.app.seekPauseHint(true, 4000))
        assertEquals("Поиск доступен через 5 с", fm.heterodyne.app.seekPauseHint(true, 4001))
        assertEquals("Поиск доступен через 1 с", fm.heterodyne.app.seekPauseHint(true, 1))
        assertEquals(null, fm.heterodyne.app.seekPauseHint(false, 4000))
    }

    @Test fun seekGroupKeepsFixedWidthInParkingPauseAndDriving() {
        val driving = mutableStateOf(false)
        val state = mutableStateOf(TransitionState.Idle)
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(state.value, null, TuneTarget(Band.FM, 87500)),
                true, EfirActions(), driving = driving.value)
        }
        listOf(false to TransitionState.Idle, false to TransitionState.CommandPause,
            true to TransitionState.CommandPause, true to TransitionState.Idle).forEach { (moving, transition) ->
            compose.runOnIdle { driving.value = moving; state.value = transition }
            compose.onNodeWithTag("seek-group").assertWidthIsEqualTo(224.dp)
        }
    }

    @Test fun seekLabelFitsBetweenDirectionalButtonsInParkingAndPause() {
        val state = mutableStateOf(TransitionState.Idle)
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(state.value, null, TuneTarget(Band.FM, 87500)), true, EfirActions())
        }
        listOf(TransitionState.Idle to "Поиск", TransitionState.CommandPause to "Поиск · пауза").forEach { (transition, text) ->
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

    @Test fun stepPauseKeepsLabelAt16SpAndPauseInSeparateText() {
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(TransitionState.CommandPause, null, TuneTarget(Band.FM, 87500)),
                true, EfirActions())
        }
        listOf("Шаг ◀ · пауза", "Шаг ▶ · пауза").forEach { description ->
            val label = compose.onNode(hasText("Шаг") and hasAnyAncestor(hasText(description)),
                useUnmergedTree = true)
            val results = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals("Шаг", results.single().layoutInput.text.text)
            assertEquals(16f, results.single().layoutInput.style.fontSize.value)
            val pause = compose.onNode(hasText("пауза") and hasAnyAncestor(hasText(description)),
                useUnmergedTree = true)
            val pauseResults = mutableListOf<TextLayoutResult>()
            pause.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(pauseResults) }
            assertEquals("пауза", pauseResults.single().layoutInput.text.text)
        }
    }

    @Test fun commandPauseLabelsRequireConnectedReceiver() {
        val connected = mutableStateOf(true)
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(TransitionState.CommandPause, null, TuneTarget(Band.FM, 87500)),
                connected.value, EfirActions())
        }
        listOf("Шаг ◀ · пауза", "Шаг ▶ · пауза", "Поиск · пауза").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.runOnIdle { connected.value = false }
        compose.onAllNodesWithText("пауза", substring = true).assertCountEquals(0)
        listOf("Шаг ◀", "Шаг ▶").forEach { compose.onNodeWithText(it).assertIsNotEnabled() }
        compose.onNodeWithText("Поиск").assertIsDisplayed()
    }

    @Test fun drivingParkingTextAndIconUseDisabledText() {
        var expected = Color.Unspecified
        compose.setContent {
            expected = LocalAppearanceColors.current["disabledText"]
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)),
                true, EfirActions(), driving = true)
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
                    EfirScreen(StationsScreenState(initial, listOf(catalog)),
                        TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)), true,
                        EfirActions(), parkLargeIcon = large.value)
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
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)), true,
                EfirActions(parked = { clicks++ }), driving = driving.value,
                parkShowLabel = label.value, parkLargeIcon = large.value)
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
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, null), true,
                EfirActions(play = { starts++ }), noFocus = true)
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("Нет фокуса, нажмите Старт")
        compose.onNodeWithText("Старт").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun compactCatalogKeepsOnePlateVisibleWithAllActionMessages() {
        val screen = mutableStateOf(StationsScreenState(StationsState.defaults(), listOf(catalog),
            recovered = true, error = "Ошибка приёмника ".repeat(20)))
        compose.setContent {
            Box(Modifier.requiredSize(935.dp, 493.dp)) {
                EfirScreen(screen.value, TransitionSnapshot(TransitionState.Unconfirmed, null, null),
                    false, EfirActions())
            }
        }
        compose.onNodeWithTag("catalog-grid").assertHeightIsAtLeast(tokenDp("size.plate.heightCompact"))
        compose.onNodeWithText("Справочная", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Проверить связь").assertHeightIsEqualTo(tokenDp("size.touch.min"))
        compose.onNodeWithText("Подключить приёмник").assertHeightIsEqualTo(tokenDp("size.touch.min"))
        val bodyHeight = compose.onNodeWithTag("efir-body").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { screen.value = screen.value.copy(state = null) }
        // Loading has no catalog yet; its extra message must not take more body space.
        assertEquals(bodyHeight, compose.onNodeWithTag("efir-body").fetchSemanticsNode().boundsInRoot.height, .5f)
    }

    @Test fun longestReasonFitsCompactStatusWithoutVisualOverflow() {
        compose.setContent {
            Box(Modifier.requiredSize(935.dp, 493.dp)) {
                EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)),
                    TransitionSnapshot(TransitionState.Unresponsive, null, null), true, EfirActions())
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
        val many = catalog.copy(stations = (0 until 20).map {
            CatalogStation("s$it", "Станция $it", StationBand.FM, 88000 + it * 100)
        })
        compose.setContent {
            Box(Modifier.requiredSize(935.dp, 493.dp)) {
                EfirScreen(StationsScreenState(StationsState.defaults(), listOf(many)),
                    TransitionSnapshot(TransitionState.Disconnected, null, null), false, EfirActions())
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
        val connection = mutableStateOf(ConnectionState.Disconnected)
        compose.setContent {
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)),
                connection.value == ConnectionState.Observing, EfirActions(), connectionState = connection.value)
        }
        listOf(ConnectionState.Disconnected, ConnectionState.PermissionPending, ConnectionState.DeviceFound,
            ConnectionState.Observing).forEach { state ->
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
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)), true, EfirActions())
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
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)),
                TransitionSnapshot(TransitionState.Seeking, null, TuneTarget(Band.FM, 87500)),
                true, EfirActions())
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("идёт поиск…").assertIsDisplayed()
        compose.onNodeWithText("Станция ◀").assertIsNotEnabled()
        compose.onNodeWithText("Поиск", substring = false).assertIsDisplayed()
    }

    @Test fun expiredSeekShowsIncompleteAndKeepsCommandsAvailable() {
        compose.setContent {
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)),
                TransitionSnapshot(TransitionState.SeekIncomplete, null, TuneTarget(Band.FM, 87500)),
                true, EfirActions())
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("Поиск не завершён").assertIsDisplayed()
        compose.onNodeWithText("идёт поиск…").assertDoesNotExist()
        compose.onNodeWithText("Шаг ◀").assertIsEnabled()
        compose.onNodeWithText("Справочная", substring = true).assertIsEnabled()
    }

    @Test fun nonEmptyActiveSelectionDoesNotShowEmptySelectionLabel() {
        val state = StationsState.defaults().let { state ->
            val region = state.regions.getValue("moskva")
            state.copy(regions = state.regions + ("moskva" to region.replaceActive(region.active.add(first).value!!)))
        }
        compose.setContent {
            EfirScreen(StationsScreenState(state, listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)), true, EfirActions())
        }
        compose.onNodeWithText("Подборка пуста — добавьте станции в режиме стоянки").assertDoesNotExist()
    }

    @Test fun referenceLayoutAndCompactBreakpointKeepGridScrollable() {
        val window = mutableStateOf(1100.dp to 580.dp)
        val many = catalog.copy(stations = (0 until 20).map {
            CatalogStation("s$it", "Станция $it", StationBand.FM, 88000 + it * 100)
        })
        compose.setContent {
            Box(Modifier.requiredSize(window.value.first, window.value.second)) {
                EfirScreen(StationsScreenState(StationsState.defaults(), listOf(many)),
                    TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)), true, EfirActions())
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
            EfirScreen(StationsScreenState(), TransitionSnapshot(TransitionState.Disconnected, null, null),
                false, EfirActions(), driving = true)
        }
        compose.onNodeWithText("Стоянка").assertIsNotEnabled()
    }

    @Test fun drivingHidesSeekWithoutMovingNeighborsAndParkingRestoresActions() {
        val driving = mutableStateOf(false)
        val transition = mutableStateOf(TransitionState.Idle)
        val seeks = mutableListOf<Direction>()
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(transition.value, null, TuneTarget(Band.FM, 87500)), true,
                EfirActions(seek = { seeks.add(it) }), driving = driving.value)
        }
        fun neighborBounds() = listOf(
            compose.onAllNodesWithText("87,5").onFirst(),
            compose.onNodeWithText("Первая"),
            compose.onNodeWithContentDescription("FM / AM"),
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
        compose.runOnIdle { transition.value = TransitionState.CommandPause }
        compose.onNodeWithText("Поиск · пауза").assertIsDisplayed()
        assertEquals(parkedBounds, neighborBounds())
        compose.runOnIdle { driving.value = true }
        compose.onNodeWithText("Поиск · пауза").assertDoesNotExist()
        assertEquals(parkedBounds, neighborBounds())
    }

    @Test fun drivingKeepsOngoingSeekStatus() {
        val driving = mutableStateOf(false)
        compose.setContent {
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)),
                TransitionSnapshot(TransitionState.Seeking, null, TuneTarget(Band.FM, 87500)),
                true, EfirActions(), driving = driving.value)
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("идёт поиск…")
        compose.runOnIdle { driving.value = true }
        compose.onNodeWithTag("efir-status").assertTextEquals("идёт поиск…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Поиск ◀").assertDoesNotExist()
        compose.onNodeWithContentDescription("Поиск ▶").assertDoesNotExist()
    }

    @Test fun drivingKeepsDisabledReasonWhenSeekIsHidden() {
        val connection = mutableStateOf(ConnectionState.Disconnected)
        compose.setContent {
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)),
                connection.value == ConnectionState.Observing, EfirActions(), driving = true,
                connectionState = connection.value)
        }
        listOf(
            ConnectionState.Disconnected to "Приёмник отключён",
            ConnectionState.PermissionPending to "Нужно разрешение USB",
            ConnectionState.DeviceFound to "Подключение идёт",
            ConnectionState.Observing to "Подборка пуста",
        ).forEach { (state, reason) ->
            compose.runOnIdle { connection.value = state }
            compose.onNodeWithTag("efir-status").assertTextEquals(reason).assertIsDisplayed()
            compose.onNodeWithContentDescription("Поиск ◀").assertDoesNotExist()
            compose.onNodeWithContentDescription("Поиск ▶").assertDoesNotExist()
        }
    }

    @Test fun catalogSelectionRequestsStationWithoutChangingConfirmedFrequency() {
        val selected = mutableListOf<StationEntry>()
        val catalog = Catalog(1, "test", Region("moskva", "Москва"), CatalogSource("vcfm.ru", "https://vcfm.ru"),
            listOf(CatalogStation("a", "Авторадио", StationBand.FM, 90300)))
        compose.setContent {
            EfirScreen(StationsScreenState(StationsState.defaults(), listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)),
                connected = true, actions = EfirActions(select = { selected.add(it) }))
        }
        compose.onNodeWithText("Авторадио", substring = true).performClick()
        assertEquals(listOf(StationEntry(StationBand.FM, 90300, "Авторадио", "a")), selected)
        compose.onAllNodesWithText("87,5").onFirst().assertIsDisplayed()
    }

    @Test fun statusLineShowsHighestReasonThenRegionWithoutDuplicatingBottomText() {
        val connection = mutableStateOf(ConnectionState.Disconnected)
        val screen = mutableStateOf(StationsState.defaults())
        compose.setContent {
            EfirScreen(StationsScreenState(screen.value, listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)),
                connection.value == ConnectionState.Observing, EfirActions(), connectionState = connection.value)
        }
        compose.onNodeWithTag("efir-status").assertTextEquals("Приёмник отключён").assertIsDisplayed()
        compose.onAllNodesWithText("Приёмник отключён").assertCountEquals(1)
        // Table: command buttons and both types of plate share the visible receiver reason.
        listOf("Старт", "Станция ◀", "Станция ▶", "Шаг ◀", "Шаг ▶").forEach {
            compose.onNodeWithText(it).assertIsNotEnabled()
        }
        listOf("Поиск ◀", "Поиск ▶", "FM / AM").forEach {
            compose.onNodeWithContentDescription(it).assertIsNotEnabled()
        }
        compose.onNodeWithText("Справочная", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Подключить приёмник").assertIsEnabled()
        listOf(
            ConnectionState.PermissionPending to "Нужно разрешение USB",
            ConnectionState.DeviceFound to "Подключение идёт",
            ConnectionState.Open to "Подключение идёт",
        ).forEach { (state, text) ->
            compose.runOnIdle { connection.value = state }
            compose.onNodeWithTag("efir-status").assertTextEquals(text).assertIsDisplayed()
            compose.onAllNodesWithText(text).assertCountEquals(1)
            compose.onNodeWithText("Старт").assertIsNotEnabled()
        }
        compose.runOnIdle { connection.value = ConnectionState.Observing }
        compose.onNodeWithTag("efir-status").assertTextEquals("Подборка пуста")
        compose.onNodeWithText("Станция ◀").assertIsNotEnabled()
        compose.onNodeWithText("Станция ▶").assertIsNotEnabled()
        listOf("Старт", "Шаг ◀", "Шаг ▶").forEach { compose.onNodeWithText(it).assertIsEnabled() }
        compose.onNodeWithText("Справочная", substring = true).assertIsEnabled()
        compose.runOnIdle { screen.value = initial }
        compose.onNodeWithTag("efir-status").assertTextEquals("FM · Москва")
        compose.runOnIdle { connection.value = ConnectionState.Disconnected }
        compose.onNodeWithText("1 · Первая", substring = true).assertIsNotEnabled()
    }

    private val catalog = Catalog(1, "test", Region("moskva", "Москва"), CatalogSource("vcfm.ru", "https://vcfm.ru"),
        listOf(CatalogStation("b", "Справочная", StationBand.FM, 90300)))
    private val first = StationEntry(StationBand.FM, 87500, "Первая")
    private val initial = StationsState.defaults().let { state ->
        val region = state.regions.getValue("moskva")
        val populated = region.replaceActive(region.active.add(first).value!!)
        state.copy(regions = state.regions + ("moskva" to populated.createSelection("Дорога").value!!))
    }

    @Test fun driverControlsDispatchBothDirectionsAndOpenParking() {
        val events = mutableListOf<String>()
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, null, TuneTarget(Band.FM, 87500)), true,
                EfirActions(station = { events.add("station:$it") }, step = { events.add("step:$it") },
                    seek = { events.add("seek:$it") }, band = { events.add("band") },
                    parked = { events.add("parked") }, activate = { events.add("selection:$it") },
                    play = { events.add("play") }))
        }
        listOf("Станция ◀", "Станция ▶", "Шаг ◀", "Шаг ▶", "Поиск ◀", "Поиск ▶",
            "FM / AM", "Подборка: Дорога", "Старт", "Стоянка").forEach {
            if (it == "Подборка: Дорога") compose.onNodeWithText("Подборка: Основной").performClick()
            val node = if (it == "FM / AM" || it.startsWith("Поиск")) compose.onNodeWithContentDescription(it) else compose.onNodeWithText(it)
            node.assertWidthIsAtLeast(tokenDp("size.touch.min")).assertHeightIsAtLeast(tokenDp("size.touch.min")).performClick()
        }
        assertEquals(listOf("station:Down", "station:Up", "step:Down", "step:Up", "seek:Down", "seek:Up",
            "band", "selection:Дорога", "play", "parked"), events)
        compose.onNodeWithText("Сохранить текущую частоту").assertDoesNotExist()
        compose.onNodeWithText("Отменить поиск").assertDoesNotExist()
    }

    @Test fun disconnectedModuleDisablesStationCardsAndCommands() {
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(TransitionState.Disconnected, null, TuneTarget(Band.FM, 87500)), false, EfirActions())
        }
        compose.onNodeWithText("Приёмник отключён").assertIsDisplayed()
        listOf("Станция ▶", "Шаг ◀", "Поиск ▶", "FM / AM", "Старт").forEach {
            val node = if (it == "FM / AM" || it.startsWith("Поиск")) compose.onNodeWithContentDescription(it) else compose.onNodeWithText(it)
            node.assertIsNotEnabled()
        }
        compose.onNodeWithText("Справочная", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Подборка: Основной").assertIsEnabled()
        compose.onNodeWithText("Стоянка").assertIsEnabled()
    }

    @Test fun pendingTargetStaysSelectedWhileIdleDuringCommandLimit() {
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)),
                TransitionSnapshot(TransitionState.Idle, TuneTarget(Band.FM, 90300), TuneTarget(Band.FM, 87500)), true, EfirActions())
        }
        compose.onNodeWithText("Справочная", substring = true).assertIsSelected()
    }

    @Test fun transitionBannersFollowControllerSnapshotAndKeepLastConfirmedFrequency() {
        val snapshot = mutableStateOf(TransitionSnapshot(TransitionState.Waiting,
            TuneTarget(Band.FM, 90300), TuneTarget(Band.FM, 87500)))
        var checks = 0
        compose.setContent {
            EfirScreen(StationsScreenState(initial, listOf(catalog)), snapshot.value, true,
                EfirActions(check = { checks++ }))
        }
        compose.onNodeWithText("ожидание").assertIsDisplayed()
        compose.onNodeWithText("Справочная", substring = true).assertIsSelected()
        compose.onAllNodesWithText("87,5").onFirst().assertIsDisplayed()
        listOf(
            TransitionState.Unconfirmed to "Нет ответа от приёмника",
            TransitionState.Unresponsive to "Модуль не отвечает, переподключите USB",
            TransitionState.CommandPause to "FM · Москва",
            TransitionState.Seeking to "идёт поиск…",
        ).forEach { (state, banner) ->
            compose.runOnIdle { snapshot.value = snapshot.value.copy(state = state, target = null) }
            compose.onNodeWithText(banner).assertIsDisplayed()
            if (state == TransitionState.Unconfirmed) compose.onNodeWithText("Проверить связь").performClick()
        }
        assertEquals(1, checks)
        compose.runOnIdle { snapshot.value = snapshot.value.copy(state = TransitionState.Idle) }
        compose.onNodeWithText("идёт поиск…").assertDoesNotExist()
    }
}
