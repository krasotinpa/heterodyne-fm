package fm.heterodyne.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fm.heterodyne.app.StationsScreenState
import fm.heterodyne.app.receiver.ReceiverKind
import fm.heterodyne.app.stations.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1200dp-h580dp-land")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun groupsFollowOrderAndHiddenGroupsLeaveNoSpace() {
        val kinds = mutableStateOf(listOf(ReceiverKind.Module, ReceiverKind("rtl-sdr-v4")))
        val receiver = mutableStateOf<ReceiverKind?>(ReceiverKind.Module)
        compose.setContent {
            ParkingScreen(StationsScreenState(StationsState.defaults()), false, null, ParkingActions(), {},
                ThemeChoice.Auto, Palette.Coral, {}, {}, receiverKinds = kinds.value,
                selectedReceiver = receiver.value, diagnostics = {})
        }
        compose.onNodeWithText("Настройки").performClick()
        val headings = listOf("Приёмник", "Запуск", "Поиск и Переход", "Вид", "Регион")
        val positions = headings.map { compose.onNodeWithText(it).getUnclippedBoundsInRoot().top.value }
        assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
        compose.runOnIdle { kinds.value = listOf(ReceiverKind("rtl-sdr-v4")); receiver.value = ReceiverKind("rtl-sdr-v4") }
        compose.onNodeWithText("Приёмник").assertDoesNotExist()
        compose.onNodeWithText("Поиск и Переход").assertDoesNotExist()
        compose.onNodeWithText("Сигнал Перехода").assertDoesNotExist()
        compose.onNodeWithText("Предел Поиска").assertDoesNotExist()
        compose.onNodeWithText("Запуск").assertIsDisplayed()
        val firstTop = compose.onNodeWithText("Запуск").getUnclippedBoundsInRoot().top.value
        assertEquals(positions.first(), firstTop, 0.5f)
        compose.onNodeWithText("О приложении").performClick()
        compose.onNodeWithText("Настройки").performClick()
        assertEquals(firstTop, compose.onNodeWithText("Запуск").getUnclippedBoundsInRoot().top.value, 0.5f)
    }

    @Test fun settingsTargetsAndGridGapsMatchSpecification() {
        compose.setContent {
            ParkingScreen(StationsScreenState(StationsState.defaults()), false, null, ParkingActions(), {}, {})
        }
        compose.onNodeWithText("Настройки").performClick()
        val targets = listOf("Играть последнюю Станцию при запуске", "Выключено", "Тон", "Звук клавиши", "Тишина") +
            (10..60 step 5).map { "$it с" } + ThemeChoice.entries.map { it.title } +
            Palette.entries.map { it.title } + ScaleMarker.entries.map { it.title } +
            listOf("Вкл", "Выкл", "Обычный", "Крупный", "Регион:  ›")
        targets.forEach { title ->
            compose.onNodeWithText(title).performScrollTo().assertHeightIsAtLeast(64.dp).assertWidthIsAtLeast(64.dp)
        }
        fun row(labels: List<String>, width: Int? = null) {
            compose.onNodeWithText(labels.first()).performScrollTo()
            labels.forEach { if (width != null) compose.onNodeWithText(it).assertWidthIsEqualTo(width.dp) }
            labels.zipWithNext().forEach { (a, b) ->
                val left = compose.onNodeWithText(a).getUnclippedBoundsInRoot()
                val right = compose.onNodeWithText(b).getUnclippedBoundsInRoot()
                assertEquals(left.top.value, right.top.value, 0.5f)
                assertEquals(12f, (right.left - left.right).value, 0.5f)
            }
        }
        row(ThemeChoice.entries.map { it.title })
        row(listOf("Выключено", "Тон", "Звук клавиши", "Тишина"), 195)
        row(Palette.entries.map { it.title }, 195)
        row(ScaleMarker.entries.map { it.title }, 153)
        row((10..35 step 5).map { "$it с" }, 126)
        row((40..60 step 5).map { "$it с" }, 126)
        val upper = compose.onNodeWithText("10 с").getUnclippedBoundsInRoot()
        val lower = compose.onNodeWithText("40 с").getUnclippedBoundsInRoot()
        assertEquals(12f, (lower.top - upper.bottom).value, 0.5f)
        row(listOf("Вкл", "Выкл"))
        row(listOf("Обычный", "Крупный"))
        compose.onNodeWithText("Играть последнюю Станцию при запуске").performScrollTo().assertWidthIsEqualTo(816.dp)
    }

    @Test fun segmentLabelsFitAtNormalAndLargeFontScale() {
        val scale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                Box(Modifier.requiredSize(1100.dp, 580.dp)) {
                    ParkingScreen(StationsScreenState(StationsState.defaults()), false, null, ParkingActions(), {},
                        ThemeChoice.Auto, Palette.Coral, {}, {},
                        receiverKinds = listOf(ReceiverKind.Module, ReceiverKind("test")),
                        receiverName = { if (it == ReceiverKind.Module) "Приёмник 1" else "Приёмник 2" }, diagnostics = {})
                }
            }
        }
        compose.onNodeWithText("Настройки").performClick()
        listOf(1f, 1.3f).forEach { value ->
            compose.runOnIdle { scale.value = value }
            val labels = listOf("Приёмник 1", "Приёмник 2") + ThemeChoice.entries.map { it.title } +
                listOf("Выключено", "Тон", "Звук клавиши", "Тишина", "Вкл", "Выкл", "Обычный", "Крупный") +
                (10..60 step 5).map { "$it с" } + "Играть последнюю Станцию при запуске"
            labels.forEach { text ->
                compose.onNodeWithText(text).performScrollTo()
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(text, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val layout = results.single()
                assertEquals(text, 1, layout.layoutInput.maxLines)
                assertFalse(text, layout.layoutInput.softWrap)
                assertEquals(text, 1, layout.lineCount)
                assertFalse("$text scale=$value size=${layout.size} width=${layout.didOverflowWidth} height=${layout.didOverflowHeight} lines=${layout.lineCount} constraints=${layout.layoutInput.constraints}", layout.hasVisualOverflow)
            }
        }
    }

    @Test fun obsoleteRegionDeletionRequiresConfirmationAndIsHiddenWhileDriving() {
        val removed = mutableListOf<String>()
        val driving = mutableStateOf(false)
        val state = StationsState("moskva", mapOf("retired" to RegionSelections.defaults()))
        val catalog = Catalog(1, "test", Region("moskva", "Москва"), CatalogSource("test", "https://example.org"), emptyList())
        compose.setContent {
            ParkingScreen(StationsScreenState(state, listOf(catalog)), driving.value, null,
                ParkingActions(deleteRegion = { removed.add(it) }), {}, {})
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Регион: Москва ›").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Удалить Регион Москва").assertDoesNotExist()
        val choice = compose.onNodeWithText("retired · устарел").getUnclippedBoundsInRoot()
        val delete = compose.onNodeWithContentDescription("Удалить Регион retired").getUnclippedBoundsInRoot()
        assertEquals(12f, (delete.left - choice.right).value, 0.5f)
        compose.onNodeWithContentDescription("Удалить Регион retired").performClick()
        assertTrue(removed.isEmpty())
        val cancel = compose.onNodeWithText("Отмена").getUnclippedBoundsInRoot()
        val confirm = compose.onNodeWithText("Удалить Регион и Подборки").getUnclippedBoundsInRoot()
        assertEquals(12f, (confirm.left - cancel.right).value, 0.5f)
        compose.onNodeWithText("Отмена").performClick()
        assertTrue(removed.isEmpty())
        compose.onNodeWithContentDescription("Удалить Регион retired").performClick()
        compose.onNodeWithText("Удалить Регион и Подборки").performClick()
        assertEquals(listOf("retired"), removed)
        compose.runOnIdle { driving.value = true }
        compose.onNodeWithContentDescription("Удалить Регион retired").assertDoesNotExist()
    }

    @Test fun obsoleteRegionRemainsInListAndCanBeSelected() {
        val selected = mutableListOf<String>()
        val state = StationsState("moskva", mapOf("retired" to RegionSelections.defaults()))
        val catalog = Catalog(1, "test", Region("moskva", "Москва"), CatalogSource("test", "https://example.org"), emptyList())
        compose.setContent {
            ParkingScreen(StationsScreenState(state, listOf(catalog)), false, null,
                ParkingActions(region = { selected.add(it) }), {}, {})
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Регион: Москва ›").performScrollTo().performClick()
        compose.onNodeWithText("retired · устарел").assertIsDisplayed().performClick()
        assertEquals(listOf("retired"), selected)
        compose.onNodeWithText("← Настройки").assertDoesNotExist()
    }

    @Test fun regionsUseRussianOrderWithYoAfterEAndIgnoreCase() {
        val catalogs = listOf("Жуков", "ёлка", "Ель", "Ёлка", "ель").mapIndexed { i, name ->
            Catalog(1, "test", Region("r$i", name), CatalogSource("test", "https://example.org"), emptyList())
        }
        compose.setContent {
            ParkingScreen(StationsScreenState(StationsState.defaults(), catalogs), false, null, ParkingActions(), {}, {})
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Регион:  ›").performScrollTo().performClick()
        val positions = listOf("Ель", "ель", "ёлка", "Ёлка", "Жуков").map {
            compose.onNodeWithText(it).getUnclippedBoundsInRoot().top.value
        }
        assertTrue(positions.toString(), positions.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun regionListUsesAllCatalogsSortsAndRevealsSelectionThenClosesOnChoice() {
        val catalogs = (0 until 52).map { i ->
            Catalog(1, "test", Region(if (i == 40) "moskva" else "r$i", "Регион %02d".format(i)),
                CatalogSource("vcfm.ru", "https://vcfm.ru"), emptyList())
        }.reversed()
        val selected = mutableListOf<String>()
        compose.setContent {
            ParkingScreen(StationsScreenState(StationsState.defaults(), catalogs), false, null,
                ParkingActions(region = { selected.add(it) }), {}, {})
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Регион: Регион 40 ›").performScrollTo()
            .assertWidthIsEqualTo(816.dp).assertHeightIsEqualTo(64.dp).performClick()
        compose.onNodeWithText("← Настройки").assertIsDisplayed()
        compose.onNodeWithText("Регион 40 ✓").assertIsDisplayed().assertIsSelected().assertHeightIsEqualTo(64.dp)
        compose.onNodeWithText("← Настройки").performClick()
        assertTrue(selected.isEmpty())
        compose.onNodeWithText("Регион: Регион 40 ›").performScrollTo().performClick()
        compose.onNodeWithText("Регион 40 ✓").assertIsDisplayed()
        compose.onNodeWithTag("settings-regions").performScrollToIndex(0)
        val first = compose.onNodeWithText("Регион 00").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Регион 01").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top < second.top)
        compose.onNodeWithTag("settings-regions").performScrollToIndex(51)
        compose.onNodeWithText("Регион 51").performClick()
        assertEquals(listOf("r51"), selected)
        compose.onNodeWithText("← Настройки").assertDoesNotExist()
        compose.onNodeWithText("Регион: Регион 40 ›").assertExists()
        compose.onNodeWithText("Регион: Регион 40 ›").performScrollTo().performClick()
        compose.onNodeWithText("Диагностика").performClick()
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("← Настройки").assertDoesNotExist()
        compose.onNodeWithText("Запуск").assertIsDisplayed()
    }
}
