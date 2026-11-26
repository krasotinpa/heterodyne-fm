package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1600dp-h800dp-land")
class ParkingScreenTest {
    @Test fun editorDispatchesOneEditPerButtonAndNoneOnSubscription() {
        val ref = SelectionRef("moskva", "Основной")
        val stations = stationsView().let { it.copy(selections = it.selections + SelectionView("Дорога", emptyList())) }
        val actions = mutableListOf<RadioAction>()
        compose.setContent { ParkingScreen(parking(stations), send = actions::add) }
        compose.runOnIdle { assertEquals(emptyList<RadioAction>(), actions) }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Создать").performClick()
        compose.onNodeWithText("Переименовать").performClick()
        compose.onNodeWithText("Удалить Подборку").performClick()
        assertEquals(listOf(SelectionEdit.Create(ref), SelectionEdit.Rename(ref, ref.name), SelectionEdit.Delete(ref))
            .map { RadioAction.Edit(it) }, actions)
    }

    @Test fun developerCanReadDiagnosticsWithoutModuleSettingsOrMeasurements() {
        val audio = AudioMetrics(null, null, null, null, null, null, null, null, null)
        val detail = DeveloperDetail(emptySet(), emptyList(), null, audio, emptyList(), null, null)
        val snapshot = parking(settings = sampleSettings.copy(transitionSignal = null, seekLimitSeconds = null),
            diagnostics = sampleDiagnostics.copy(developerEnabled = true, detail = detail),
            profiles = listOf(testOption), selected = testOption.id)
        compose.setContent { ParkingScreen(snapshot) {} }
        compose.onNodeWithText("Режим разработчика").performClick()
        compose.onNodeWithText("Звук: байты —", substring = true).assertExists()
        compose.onAllNodesWithText("null", substring = true).assertCountEquals(0)
        compose.onNodeWithText("TUNEDOWN").assertIsNotEnabled()
    }

    @Test fun catalogUpdateIsManualAndShowsBusyAndResult() {
        val screen = mutableStateOf(stationsView())
        val driving = mutableStateOf(false)
        var requests = 0
        compose.setContent {
            ParkingScreen(parking(screen.value).copy(moving = driving.value)) { if (it == RadioAction.UpdateCatalog) requests++ }
        }
        assertEquals(0, requests)
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Обновить Справочник").performScrollTo().performClick()
        assertEquals(1, requests)
        compose.runOnIdle { screen.value = screen.value.copy(catalogUpdate = CatalogUpdate.InProgress) }
        compose.onNodeWithText("Обновление Справочника…").assertIsNotEnabled()
        compose.runOnIdle { screen.value = screen.value.copy(catalogUpdate = CatalogUpdate.NoUpdate) }
        compose.onNodeWithText("Нет обновлений").assertExists()
        compose.runOnIdle { driving.value = true }
        compose.onNodeWithText("Обновить Справочник").assertDoesNotExist()
    }

    @Test fun stationFormClosesAfterSuccessfulAddCurrentFrequencyAndRename() {
        val screen = mutableStateOf(stationsView())
        compose.setContent {
            ParkingScreen(parking(screen.value, confirmed = station(102000))) { action ->
                val change = (action as? RadioAction.Edit)?.change
                val selection = screen.value.selections.single()
                val entries = when (change) {
                    is SelectionEdit.Add -> selection.entries + change.station
                    is SelectionEdit.EditName -> selection.entries.map {
                        if (it.frequencyKhz == change.frequencyKhz) it.copy(name = change.newName) else it
                    }
                    else -> selection.entries
                }
                screen.value = screen.value.copy(selections = listOf(selection.copy(entries = entries)))
            }
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Добавить Станцию").performClick()
        compose.onNodeWithTag("station-name").performTextReplacement("Новая")
        compose.onNodeWithTag("station-frequency").performTextReplacement("101,7")
        compose.onNodeWithText("Сохранить Станцию").performClick()
        compose.onNodeWithTag("station-name").assertDoesNotExist()
        compose.onNodeWithText("Сохранить текущую частоту").performClick()
        compose.onNodeWithTag("station-name").performTextReplacement("Текущая")
        compose.onNodeWithText("Сохранить Станцию").performClick()
        compose.onNodeWithTag("station-name").assertDoesNotExist()
        compose.onNodeWithTag("station:101700").performClick()
        compose.onNodeWithTag("station-name").performTextReplacement("Новое имя")
        compose.onNodeWithText("Сохранить Станцию").performClick()
        compose.onNodeWithTag("station-name").assertDoesNotExist()
        compose.onNodeWithText("Новое имя · 101700 кГц").assertExists()
    }

    @Test fun currentFrequencyPreservesKhzAndRejectsFrequencyOutsideStep() {
        val added = mutableListOf<StationView>()
        compose.setContent {
            ParkingScreen(parking(confirmed = station(101750))) { action ->
                ((action as? RadioAction.Edit)?.change as? SelectionEdit.Add)?.let { added.add(it.station) }
            }
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Сохранить текущую частоту").performClick()
        compose.onNodeWithTag("station-frequency").assertTextEquals("101,750")
        compose.onNodeWithTag("station-name").performTextReplacement("Моя")
        compose.onNodeWithText("Сохранить Станцию").performClick()
        compose.onNodeWithText("Введите FM-частоту 87,5–108,0 МГц с шагом 0,1").assertIsDisplayed()
        compose.onNodeWithTag("station-name").assertIsDisplayed()
        assertEquals(emptyList<StationView>(), added)
    }

    @Test fun stationFormAddsNamedMhzFrequencyAndEditsOnlyName() {
        val entry = station(90300, "Прежнее")
        val state = stationsView(entries = listOf(entry))
        val added = mutableListOf<StationView>()
        val edited = mutableListOf<SelectionEdit.EditName>()
        compose.setContent {
            ParkingScreen(parking(state, confirmed = entry)) { action ->
                when (val change = (action as? RadioAction.Edit)?.change) {
                    is SelectionEdit.Add -> added.add(change.station)
                    is SelectionEdit.EditName -> edited.add(change)
                    else -> Unit
                }
            }
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Добавить Станцию").performClick()
        compose.onNodeWithTag("station-name").assertTextEquals("")
        compose.onNodeWithTag("station-frequency").assertTextEquals("")
        compose.onNodeWithText("Сохранить Станцию").performClick()
        compose.onNodeWithText("Введите имя Станции: от 1 до 32 символов").assertIsDisplayed()
        compose.onNodeWithTag("station-name").performTextReplacement("я".repeat(33))
        compose.onNodeWithText("Сохранить Станцию").performClick()
        compose.onNodeWithText("Введите имя Станции: от 1 до 32 символов").assertIsDisplayed()
        compose.onNodeWithTag("station-name").performTextReplacement("  Новая  ")
        for (invalid in listOf("", "87,4", "108,1", "101,75", "текст")) {
            compose.onNodeWithTag("station-frequency").performTextReplacement(invalid)
            compose.onNodeWithText("Сохранить Станцию").performClick()
            compose.onNodeWithText("Введите FM-частоту 87,5–108,0 МГц с шагом 0,1").assertIsDisplayed()
        }
        compose.onNodeWithTag("station-frequency").performTextReplacement("101,7")
        compose.onNodeWithText("Сохранить Станцию").performClick()
        assertEquals(listOf(StationView(101700, "Новая")), added)
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithTag("station:90300").performClick()
        compose.onNodeWithTag("station-name").assertTextEquals("Прежнее").performTextReplacement("  Моё  ")
        compose.onNodeWithTag("station-frequency").assertTextEquals("90,3").assertIsNotEnabled()
        compose.onNodeWithText("Сохранить Станцию").performClick()
        assertEquals(listOf(SelectionEdit.EditName(SelectionRef("moskva", "Основной"), 90300, "Моё")), edited)
    }

    @Test fun saveCurrentFrequencyRequiresNameAndShowsDuplicateInForm() {
        val entry = StationView(101700, "Имя из эфира")
        val added = mutableListOf<StationView>()
        val screen = mutableStateOf(stationsView())
        compose.setContent {
            ParkingScreen(parking(screen.value, confirmed = entry)) { action ->
                when (val change = (action as? RadioAction.Edit)?.change) {
                    is SelectionEdit.Add -> {
                        added.add(change.station)
                        screen.value = screen.value.copy(editIssue = EditIssue.DuplicateFrequency)
                    }
                    SelectionEdit.ClearIssue -> screen.value = screen.value.copy(editIssue = null)
                    else -> Unit
                }
            }
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Сохранить текущую частоту").performClick()
        assertEquals(emptyList<StationView>(), added)
        compose.onNodeWithTag("station-frequency").assertTextEquals("101,7")
        compose.onNodeWithTag("station-name").assertTextEquals("")
        compose.onNodeWithText("Сохранить Станцию").performClick()
        compose.onNodeWithText("Введите имя Станции: от 1 до 32 символов").assertIsDisplayed()
        compose.onNodeWithTag("station-name").performTextReplacement("Моя")
        compose.onNodeWithText("Сохранить Станцию").performClick()
        compose.onNodeWithText("Частота уже есть в Подборке").assertIsDisplayed()
        compose.onNodeWithTag("station-name").assertIsDisplayed()
        compose.onNodeWithTag("station-frequency").performTextReplacement("102,0")
        compose.onNodeWithText("Частота уже есть в Подборке").assertDoesNotExist()
        assertEquals(listOf(StationView(101700, "Моя")), added)
    }


    @Test fun nameErrorAppearsBelowFieldAndClearsOnInputAndSelectionChange() {
        val state = stationsView().let { it.copy(selections = it.selections + SelectionView("Дорога", emptyList())) }
        val screen = mutableStateOf(state)
        compose.setContent {
            ParkingScreen(parking(screen.value)) { action ->
                when ((action as? RadioAction.Edit)?.change) {
                    is SelectionEdit.Rename -> screen.value = screen.value.copy(editIssue = EditIssue.DuplicateName)
                    SelectionEdit.ClearIssue -> screen.value = screen.value.copy(editIssue = null)
                    else -> Unit
                }
            }
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Дорога").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Основной")
        compose.onNodeWithText("Переименовать").performClick()
        val error = compose.onNodeWithText("Подборка с таким именем уже есть")
        error.assertIsDisplayed()
        val fieldBounds = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertEquals(true, error.fetchSemanticsNode().boundsInRoot.top >= fieldBounds.bottom)
        compose.onNode(hasSetTextAction()).performTextReplacement("Новое имя")
        error.assertDoesNotExist()
        compose.onNodeWithText("Переименовать").performClick()
        error.assertIsDisplayed()
        compose.onNodeWithText("Основной").performClick()
        error.assertDoesNotExist()
    }

    @Test fun nameErrorClearsWhenLeavingSelections() {
        val screen = mutableStateOf(stationsView())
        compose.setContent {
            ParkingScreen(parking(screen.value)) { action ->
                when ((action as? RadioAction.Edit)?.change) {
                    is SelectionEdit.Rename -> screen.value = screen.value.copy(editIssue = EditIssue.DuplicateName)
                    SelectionEdit.ClearIssue -> screen.value = screen.value.copy(editIssue = null)
                    else -> Unit
                }
            }
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Переименовать").performClick()
        compose.onNodeWithText("Подборка с таким именем уже есть").assertIsDisplayed()
        compose.onNodeWithText("Настройки").performClick()
        compose.runOnIdle { assertEquals(null, screen.value.editIssue) }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Подборка с таким именем уже есть").assertDoesNotExist()
    }

    @Test fun scaleMarkerSettingSelectsEveryShapeImmediately() {
        val marker = mutableStateOf(ScaleMarker.Pin)
        compose.setContent {
            ParkingScreen(parking(settings = sampleSettings.copy(scaleMarker = marker.value))) { action ->
                val change = (action as? RadioAction.ChangeSetting)?.change
                if (change is SettingChange.Marker) marker.value = change.value
            }
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Отметка на шкале").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Булавка").assertIsSelected()
        ScaleMarker.entries.forEach { shape ->
            compose.onNodeWithText(shape.title).performScrollTo().performClick().assertIsSelected()
            assertEquals(shape, marker.value)
        }
    }

    @Test fun receiverChoiceHidesModuleSettingsAndSelectsReceiver() {
        val selected = mutableStateOf(testOption.id)
        compose.setContent {
            ParkingScreen(parking(profiles = listOf(moduleOption, testOption), selected = selected.value,
                settings = if (selected.value == moduleOption.id) sampleSettings else sampleSettings.copy(seekLimitSeconds = null, transitionSignal = null))) { action ->
                val change = (action as? RadioAction.ChangeSetting)?.change
                if (change is SettingChange.Receiver) selected.value = change.id
            }
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Приёмник").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Предел Поиска").assertDoesNotExist()
        compose.onNodeWithText("Сигнал Перехода").assertDoesNotExist()
        compose.onNodeWithText("Тестовый приёмник").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Тестовый приёмник").assertIsSelected()
        compose.onNodeWithContentDescription("Модуль").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Модуль").assertIsSelected()
        compose.onNodeWithText("Предел Поиска").performScrollTo().assertIsDisplayed()
    }

    @Test fun singleKindHasNoReceiverSettingsSection() {
        compose.setContent {
            ParkingScreen(parking()) {}
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Приёмник").assertDoesNotExist()
    }
    @get:Rule val compose = createComposeRule()

    @Test fun settingsSelectParkButtonAppearanceImmediately() {
        val label = mutableStateOf(true)
        val large = mutableStateOf(false)
        compose.setContent {
            ParkingScreen(parking(settings = sampleSettings.copy(parkShowLabel = label.value, parkLargeIcon = large.value))) { action ->
                when (val change = (action as? RadioAction.ChangeSetting)?.change) {
                    is SettingChange.ParkLabel -> label.value = change.show
                    is SettingChange.ParkIcon -> large.value = change.large
                    else -> Unit
                }
            }
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Кнопка «Стоянка»").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Выкл").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Вкл").assertIsNotSelected()
        compose.onNodeWithText("Крупный").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Обычный").performClick().assertIsSelected()
        compose.onNodeWithText("Вкл").performScrollTo().performClick().assertIsSelected()
    }

    @Test fun settingsSelectTransitionSignal() {
        val method = mutableStateOf(SignalChoice.Tone)
        compose.setContent {
            HeterodyneTheme(ResolvedTheme.Dark, Palette.Coral) {
                ParkingScreen(parking(settings = sampleSettings.copy(transitionSignal = method.value))) { action ->
                val change = (action as? RadioAction.ChangeSetting)?.change
                if (change is SettingChange.TransitionSignal) method.value = change.value
            }
            }
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Сигнал Перехода").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Тон").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Выключено").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Тон").performClick().assertIsSelected()
        compose.onNodeWithText("Звук клавиши").performClick().assertIsSelected()
        compose.onNodeWithText("Тишина").performClick().assertIsSelected()
    }

    @Test fun menuOffersRegionsAndAboutWithSafeSourceLink() {
        val regions = listOf("moskva" to "Москва", "yaroslavl" to "Ярославль").map { (id, name) ->
            RegionView(id, name, false)
        }
        val selected = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { throw android.content.ActivityNotFoundException() }
            }) {
                ParkingScreen(parking(stationsView().copy(regions = regions,
                    catalogSource = CatalogSourceView("vcfm.ru", "https://vcfm.ru/")))) {
                    if (it is RadioAction.SelectRegion) selected.add(it.id)
                }
            }
        }
        compose.onNodeWithTag("parking-menu").assertWidthIsEqualTo(236.dp)
        compose.onNodeWithText("← Эфир").assertHeightIsEqualTo(56.dp)
        compose.onNodeWithText("Приёмник: не подключён").assertIsDisplayed()
        compose.onNodeWithText("Режим разработчика").assertDoesNotExist()
        listOf("Подборки", "Настройки", "Диагностика", "О приложении").forEach {
            compose.onNodeWithText(it).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Регион: Москва ›").performScrollTo().performClick()
        compose.onNodeWithText("Москва ✓").assertIsSelected()
        compose.onNodeWithText("Ярославль").performClick()
        assertEquals(listOf("yaroslavl"), selected)
        compose.onNodeWithText("О приложении").performClick()
        compose.onNodeWithText("Версия test-version").assertIsDisplayed()
        compose.onNodeWithText("Источник: vcfm.ru").performClick()
        compose.onNodeWithText("Нет приложения для открытия ссылки").assertIsDisplayed()
    }

    @Test fun settingsSelectThemeAndPaletteWithoutReopeningScreen() {
        val theme = mutableStateOf(ThemeChoice.Auto)
        val palette = mutableStateOf(Palette.Coral)
        compose.setContent {
            HeterodyneTheme(ResolvedTheme.Dark, palette.value) {
                ParkingScreen(parking(settings = sampleSettings.copy(theme = theme.value, palette = palette.value))) { action ->
                    when (val change = (action as? RadioAction.ChangeSetting)?.change) {
                        is SettingChange.Theme -> theme.value = change.value
                        is SettingChange.PaletteChoice -> palette.value = change.value
                        else -> Unit
                    }
                }
            }
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Вид").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Авто").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Коралл").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Светлая").performScrollTo().performClick()
        compose.onNodeWithText("Слива").performScrollTo().performClick()
        compose.onNodeWithText("Светлая").assertIsSelected()
        compose.onNodeWithText("Слива").assertIsSelected()
        compose.runOnIdle {
            assertEquals(ThemeChoice.Light, theme.value)
            assertEquals(Palette.Plum, palette.value)
        }
    }

    @Test fun settingsSelectSearchLimitImmediately() {
        val seconds = mutableStateOf(20)
        compose.setContent {
            ParkingScreen(parking(settings = sampleSettings.copy(seekLimitSeconds = seconds.value))) { action ->
                val change = (action as? RadioAction.ChangeSetting)?.change
                if (change is SettingChange.SeekLimit) seconds.value = change.seconds
            }
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("20 с").performScrollTo().assertIsSelected()
        compose.onNodeWithText("35 с").performScrollTo().performClick()
        compose.onNodeWithText("35 с").assertIsSelected()
        compose.runOnIdle { assertEquals(35, seconds.value) }
    }

    @Test fun settingsAutostartIsSelectedByDefault() {
        compose.setContent {
            ParkingScreen(parking()) {}
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Запуск").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Играть последнюю Станцию при запуске").performScrollTo()
            .assertIsDisplayed().assertIsSelected().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Радио включится само после запуска приложения или возврата приёмника. Смена действует со следующего запуска.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun settingsAutostartClickCallsCallbackAndUpdatesSelection() {
        val enabled = mutableStateOf(true)
        val selected = mutableListOf<Boolean>()
        compose.setContent {
            ParkingScreen(parking(settings = sampleSettings.copy(autostart = enabled.value))) { action ->
                val change = (action as? RadioAction.ChangeSetting)?.change
                if (change is SettingChange.Autostart) { selected.add(change.enabled); enabled.value = change.enabled }
            }
        }
        compose.onNodeWithText("Настройки").performClick()
        val toggle = compose.onNodeWithText("Играть последнюю Станцию при запуске")
        toggle.performScrollTo().assertIsSelected().performClick()
        toggle.assertIsNotSelected().performClick()
        toggle.assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(false, true), selected) }
    }

    @Test fun rejectedRenameKeepsSelectedSelection() {
        val state = stationsView().let { it.copy(selections = it.selections + SelectionView("Дорога", emptyList())) }
        val deleted = mutableListOf<Pair<String, String>>()
        compose.setContent {
            ParkingScreen(parking(state)) { action ->
                val change = (action as? RadioAction.Edit)?.change
                if (change is SelectionEdit.Delete) deleted.add(change.selection.regionId to change.selection.name)
            }
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Дорога").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Основной")
        compose.onNodeWithText("Переименовать").performClick()
        compose.onNodeWithText("Дорога").assertIsSelected()
        compose.onNodeWithText("Удалить Подборку").performClick()
        assertEquals(listOf("moskva" to "Дорога"), deleted)
    }

    @Test fun developerMenuTracksSwitchAndKeepsSidebarOnEveryPage() {
        val developer = mutableStateOf(false)
        val connected = mutableStateOf(false)
        var closed = 0
        compose.setContent {
            ParkingScreen(parking(connected = connected.value,
                diagnostics = sampleDiagnostics.copy(developerEnabled = developer.value)), onToolsClosed = { closed++ }) {}
        }
        compose.onNodeWithText("Режим разработчика").assertDoesNotExist()
        compose.runOnIdle { developer.value = true; connected.value = true }
        compose.onNodeWithText("Приёмник: подключён").assertIsDisplayed()
        compose.onNodeWithText("Режим разработчика").performClick()
        compose.runOnIdle { assertEquals(0, closed) }
        compose.onNodeWithText("← Эфир").assertIsDisplayed()
        compose.runOnIdle { developer.value = false }
        compose.onNodeWithText("Режим разработчика").assertDoesNotExist()
        compose.onNodeWithText("test-version").assertIsDisplayed()
        compose.onNodeWithText("← Эфир").performClick()
        assertEquals(1, closed)
    }

    @Test fun dragTargetClampsBothEdges() {
        assertEquals(2, dragTargetIndex(0, 144f, 72f, 3))
        assertEquals(0, dragTargetIndex(2, -500f, 72f, 3))
        assertEquals(2, dragTargetIndex(0, 500f, 72f, 3))
        assertEquals(1, dragTargetIndex(1, 20f, 72f, 3))
    }

    @Test fun editorAddsCatalogCopyAndLongDragRequestsNewOrder() {
        val first = StationView(90300, "Первая")
        val second = StationView(91000, "Вторая")
        val state = stationsView(entries = listOf(first, second), catalog = listOf(CatalogStationView("a", 92000, "Справочная")))
        val ref = SelectionRef("moskva", "Основной")
        val edits = mutableListOf<SelectionEdit>()
        compose.setContent {
            ParkingScreen(parking(state, confirmed = first)) {
                if (it is RadioAction.Edit && it.change != SelectionEdit.ClearIssue) edits.add(it.change)
            }
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("+").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        assertEquals(SelectionEdit.Add(ref, station(92000, "Справочная"), "a"), edits.single())
        compose.onNodeWithTag("station:90300").assertHeightIsEqualTo(56.dp)
        compose.onAllNodesWithText("×")[0].assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("station:90300").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, 80.dp.toPx()), delayMillis = 100)
        }
        val targetBounds = compose.onNodeWithTag("station:91000").fetchSemanticsNode().boundsInRoot
        val insertionBounds = compose.onNodeWithTag("insertion-after:1").fetchSemanticsNode().boundsInRoot
        assertEquals(targetBounds.bottom, insertionBounds.bottom, 1f)
        compose.onNodeWithTag("station:90300").performTouchInput { up() }
        assertEquals(SelectionEdit.Move(ref, first.frequencyKhz, 1), edits[1])
    }

    @Test fun drivingClosesEditorAndDoesNotReopenItWhenStopped() {
        val driving = mutableStateOf(false)
        compose.setContent {
            if (!driving.value) ParkingScreen(parking()) {} else EfirScreen(sampleRadio(driving = true)) {}
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Сохранить текущую частоту").assertIsNotEnabled()
        compose.runOnIdle { driving.value = true }
        compose.onNodeWithText("Сохранить текущую частоту").assertDoesNotExist()
        compose.onNodeWithTag("efir-topbar").assertExists()
        compose.runOnIdle { driving.value = false }
        compose.onNodeWithText("Подборки").assertIsDisplayed()
    }
}
