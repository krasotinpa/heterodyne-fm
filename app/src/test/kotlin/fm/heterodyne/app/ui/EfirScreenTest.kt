package fm.heterodyne.app.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fm.heterodyne.app.StationsScreenState
import fm.heterodyne.app.module.*
import fm.heterodyne.app.stations.*
import fm.heterodyne.app.stations.Band as StationBand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1600dp-h800dp-land")
class EfirScreenTest {
    @get:Rule val compose = createComposeRule()

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
        compose.onNodeWithText("87,5").assertIsDisplayed()
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
            "FM / AM", "Подборка: Дорога", "Играть", "Стоянка").forEach {
            compose.onNodeWithText(it).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
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
        compose.onNodeWithText("Модуль отключён").assertIsDisplayed()
        listOf("Станция ▶", "Шаг ◀", "Поиск ▶", "FM / AM", "Играть").forEach {
            compose.onNodeWithText(it).assertIsNotEnabled()
        }
        compose.onNodeWithText("Справочная", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Подборка: Дорога").assertIsEnabled()
        compose.onNodeWithText("Стоянка").assertIsEnabled()
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
        compose.onNodeWithText("87,5").assertIsDisplayed()
        listOf(
            TransitionState.Unconfirmed to "Нет ответа от Модуля",
            TransitionState.Unresponsive to "Модуль не отвечает, переподключите USB",
            TransitionState.CommandPause to "Пауза команды: Шаг / Поиск",
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
