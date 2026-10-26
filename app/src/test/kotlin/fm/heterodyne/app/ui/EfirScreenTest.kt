package fm.heterodyne.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
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
        compose.onNodeWithTag("rds-reserved").assertHeightIsEqualTo(22.dp)
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
            node.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
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
