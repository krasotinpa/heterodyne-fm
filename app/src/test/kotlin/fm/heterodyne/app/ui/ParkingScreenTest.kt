package fm.heterodyne.app.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import fm.heterodyne.app.BuildConfig
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import fm.heterodyne.app.stations.*
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.createComposeRule
import fm.heterodyne.app.StationsScreenState
import fm.heterodyne.app.stations.StationsState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1600dp-h800dp-land")
class ParkingScreenTest {

    @Test fun scaleMarkerSettingSelectsEveryShapeImmediately() {
        val marker = mutableStateOf(ScaleMarker.Pin)
        compose.setContent {
            ParkingScreen(StationsScreenState(StationsState.defaults()), false, null,
                ParkingActions(), {}, theme = ThemeChoice.Dark, palette = Palette.Coral,
                selectTheme = {}, selectPalette = {}, scaleMarker = marker.value,
                selectScaleMarker = { marker.value = it }, diagnostics = {})
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
        val other = fm.heterodyne.app.receiver.ReceiverKind("test")
        val profiles = fm.heterodyne.app.receiver.ReceiverProfiles.Module.withProfile(
            fm.heterodyne.app.receiver.ReceiverProfile(other, "Тестовый приёмник", 1, 2) { true })
        val selected = mutableStateOf(other)
        compose.setContent {
            ParkingScreen(StationsScreenState(StationsState.defaults()), false, null,
                ParkingActions(), {}, theme = ThemeChoice.Dark, palette = Palette.Coral,
                selectTheme = {}, selectPalette = {},
                receiverKinds = profiles.kinds, receiverName = profiles::displayName,
                selectedReceiver = selected.value, selectReceiver = { selected.value = it }, diagnostics = {})
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
            ParkingScreen(StationsScreenState(StationsState.defaults()), false, null,
                ParkingActions(), {}, diagnostics = {})
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Приёмник").assertDoesNotExist()
    }
    @get:Rule val compose = createComposeRule()

    @Test fun settingsSelectParkButtonAppearanceImmediately() {
        val label = mutableStateOf(true)
        val large = mutableStateOf(false)
        compose.setContent {
            ParkingScreen(StationsScreenState(StationsState.defaults()), false, null,
                ParkingActions(), {}, theme = ThemeChoice.Dark, palette = Palette.Coral,
                selectTheme = {}, selectPalette = {}, parkShowLabel = label.value,
                selectParkShowLabel = { label.value = it }, parkLargeIcon = large.value,
                selectParkLargeIcon = { large.value = it }, diagnostics = {})
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
        val method = mutableStateOf(fm.heterodyne.app.audio.TransitionSignalMethod.Tone)
        compose.setContent {
            HeterodyneTheme(ThemeChoice.Dark, Palette.Coral) {
                ParkingScreen(StationsScreenState(StationsState.defaults()), false, null,
                    ParkingActions(), {}, theme = ThemeChoice.Dark, palette = Palette.Coral,
                    selectTheme = {}, selectPalette = {}, signalMethod = method.value,
                    selectSignal = { method.value = it }, diagnostics = {})
            }
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Сигнал Перехода").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Тон").assertIsSelected()
        compose.onNodeWithText("Выключено").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Тон").performClick().assertIsSelected()
        compose.onNodeWithText("Звук клавиши").performClick().assertIsSelected()
        compose.onNodeWithText("Тишина").performClick().assertIsSelected()
    }

    @Test fun menuOffersRegionsAndAboutWithSafeSourceLink() {
        val regions = listOf("moskva" to "Москва", "yaroslavl" to "Ярославль").map { (id, name) ->
            Catalog(1, "test", Region(id, name), CatalogSource("vcfm.ru", "https://vcfm.ru/"), emptyList())
        }
        val selected = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { throw android.content.ActivityNotFoundException() }
            }) {
                ParkingScreen(StationsScreenState(StationsState.defaults(), regions), false, null,
                    ParkingActions(region = { selected.add(it) }), {}, {})
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
        compose.onNodeWithText("Москва").assertIsSelected()
        compose.onNodeWithText("Ярославль").performClick()
        assertEquals(listOf("yaroslavl"), selected)
        compose.onNodeWithText("О приложении").performClick()
        compose.onNodeWithText("Версия ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("Источник: vcfm.ru").performClick()
        compose.onNodeWithText("Нет приложения для открытия ссылки").assertIsDisplayed()
    }

    @Test fun settingsSelectThemeAndPaletteWithoutReopeningScreen() {
        val theme = mutableStateOf(ThemeChoice.Auto)
        val palette = mutableStateOf(Palette.Coral)
        compose.setContent {
            HeterodyneTheme(theme.value, palette.value) {
                ParkingScreen(StationsScreenState(StationsState.defaults()), false, null,
                    ParkingActions(), {}, theme = theme.value, palette = palette.value,
                    selectTheme = { theme.value = it }, selectPalette = { palette.value = it }, diagnostics = {})
            }
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Регион").assertIsDisplayed()
        compose.onNodeWithText("Авто").assertIsSelected()
        compose.onNodeWithText("Коралл").assertIsSelected()
        compose.onNodeWithText("Светлая").performClick()
        compose.onNodeWithText("Слива").performClick()
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
            ParkingScreen(StationsScreenState(StationsState.defaults()), false, null,
                ParkingActions(), {}, theme = ThemeChoice.Auto, palette = Palette.Coral,
                selectTheme = {}, selectPalette = {}, seekLimitSeconds = seconds.value,
                selectSeekLimit = { seconds.value = it }, diagnostics = {})
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("20 с").performScrollTo().assertIsSelected()
        compose.onNodeWithText("35 с").performScrollTo().performClick()
        compose.onNodeWithText("35 с").assertIsSelected()
        compose.runOnIdle { assertEquals(35, seconds.value) }
    }

    @Test fun settingsAutostartIsSelectedByDefault() {
        compose.setContent {
            ParkingScreen(StationsScreenState(StationsState.defaults()), false, null,
                ParkingActions(), {}, {})
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
            ParkingScreen(StationsScreenState(StationsState.defaults()), false, null,
                ParkingActions(), {}, theme = ThemeChoice.Auto, palette = Palette.Coral,
                selectTheme = {}, selectPalette = {}, autostartEnabled = enabled.value,
                selectAutostart = { selected.add(it); enabled.value = it }, diagnostics = {})
        }
        compose.onNodeWithText("Настройки").performClick()
        val toggle = compose.onNodeWithText("Играть последнюю Станцию при запуске")
        toggle.performScrollTo().assertIsSelected().performClick()
        toggle.assertIsNotSelected().performClick()
        toggle.assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(false, true), selected) }
    }

    @Test fun rejectedRenameKeepsSelectedSelection() {
        val base = StationsState.defaults()
        val state = base.copy(regions = base.regions + ("moskva" to
            base.regions.getValue("moskva").createSelection("Дорога").value!!))
        val deleted = mutableListOf<Pair<String, String>>()
        compose.setContent {
            ParkingScreen(StationsScreenState(state), false, null,
                ParkingActions(delete = { regionId, name -> deleted.add(regionId to name) }), {}, {})
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
            ParkingScreen(StationsScreenState(StationsState.defaults()), false, null,
                ParkingActions(), { closed++ }, ThemeChoice.Auto, Palette.Coral, {}, {},
                connected = connected.value, developerEnabled = developer.value,
                developerTools = { Label("Инструменты", 18) }, diagnostics = { Label("Проверка", 18) })
        }
        compose.onNodeWithText("Режим разработчика").assertDoesNotExist()
        compose.runOnIdle { developer.value = true; connected.value = true }
        compose.onNodeWithText("Приёмник: подключён").assertIsDisplayed()
        compose.onNodeWithText("Режим разработчика").performClick()
        compose.onNodeWithText("Инструменты").assertIsDisplayed()
        compose.onNodeWithText("← Эфир").assertIsDisplayed()
        compose.runOnIdle { developer.value = false }
        compose.onNodeWithText("Инструменты").assertDoesNotExist()
        compose.onNodeWithText("Режим разработчика").assertDoesNotExist()
        compose.onNodeWithText("Проверка").assertIsDisplayed()
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
        val first = StationEntry(90300, "Первая")
        val second = StationEntry(91000, "Вторая")
        val base = StationsState.defaults()
        val region = base.regions.getValue("moskva")
        val state = base.copy(regions = base.regions + ("moskva" to
            region.replaceActive(region.active.add(first).value!!.add(second).value!!)))
        val catalog = Catalog(1, "test", Region("moskva", "Москва"), CatalogSource("vcfm.ru", "https://vcfm.ru"),
            listOf(CatalogStation("a", "Справочная", 92000)))
        val added = mutableListOf<Triple<String, String, StationEntry>>()
        val moved = mutableListOf<Pair<Triple<String, String, StationEntry>, Int>>()
        compose.setContent {
            ParkingScreen(StationsScreenState(state, listOf(catalog)), false, first,
                ParkingActions(
                    add = { regionId, name, entry -> added.add(Triple(regionId, name, entry)) },
                    move = { regionId, name, entry, to -> moved.add(Triple(regionId, name, entry) to to) },
                ), {}, {})
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("+").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        assertEquals(listOf(Triple("moskva", "Основной", catalog.stations.single().copyEntry())), added)
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
        assertEquals(listOf(Triple("moskva", "Основной", first) to 1), moved)
    }

    @Test fun drivingClosesEditorAndDoesNotReopenItWhenStopped() {
        val driving = mutableStateOf(false)
        var closed = 0
        compose.setContent {
            ParkingScreen(StationsScreenState(StationsState.defaults()), driving.value, null,
                ParkingActions(), { closed++ }, {})
        }
        compose.onNodeWithText("Подборки").performClick()
        compose.onNodeWithText("Сохранить текущую частоту").assertIsNotEnabled()
        compose.runOnIdle { driving.value = true }
        compose.onNodeWithText("Сохранить текущую частоту").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closed) }
        compose.runOnIdle { driving.value = false }
        compose.onNodeWithText("Подборки").assertIsDisplayed()
    }
}
