package fm.heterodyne.ui.sandbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class SandboxScenariosTest {
    @get:Rule val ui = createAndroidComposeRule<SandboxActivity>()

    private fun scenario(title: String) {
        ui.onNodeWithText("Сценарии").performClick()
        ui.onNodeWithText(title).performClick()
    }

    private fun next() = ui.onNodeWithTag("next-cue").performClick()
    private fun frequency(text: String) = ui.onNodeWithTag("efir-frequency").assertTextEquals(text)
    private fun text(value: String) = ui.onNodeWithText(value)

    @Test fun workingAndDisconnectedReceiver() {
        frequency("90,3")
        ui.onNodeWithTag("efir-name").assertTextEquals("Маяк стенда")
        text("Старт").performClick()
        text("Стоп").performClick()
        scenario("Нет USB")
        ui.onNodeWithContentDescription("Сохранённая Станция 90,3").assertExists()
        text("Подборка: Первая").performClick()
        text("Подборка: Вторая").performClick()
        text("Подборка: Вторая").assertExists()
        frequency("90,3")
    }

    @Test fun targetAndConfirmationFollowTheScriptClock() {
        scenario("Переход")
        frequency("90,3")
        ui.onNodeWithContentDescription("Цель 101,2").assertExists()
        ui.onNodeWithContentDescription("Подтверждённая Станция 90,3").assertExists()
        next()
        ui.onNodeWithTag("efir-status").assertTextEquals("ожидание")
        next()
        frequency("101,2")
        ui.onNodeWithContentDescription("Подтверждённая Станция 101,2").assertExists()
        ui.onNodeWithContentDescription("Цель 101,2").assertDoesNotExist()
        scenario("Ошибка Перехода")
        next()
        next()
        frequency("90,3")
        text("Проверить связь").assertIsEnabled().performClick()
    }

    @Test fun recreateKeepsThePendingTransitionAndManualTimeConsumesPassedCues() {
        scenario("Переход")
        next()
        ui.activityRule.scenario.recreate()
        text("301 мс").assertExists()
        ui.onNodeWithTag("efir-status").assertTextEquals("ожидание")
        ui.onNodeWithTag("next-cue").assertTextEquals("Далее · Ответ")
        text("+1 с").performClick()
        frequency("101,2")
        ui.onNodeWithTag("next-cue").assertDoesNotExist()
    }

    @Test fun manualTimeSkipsBothPassedCheckpoints() {
        scenario("Переход")
        text("+1 с").performClick()
        frequency("101,2")
        ui.onNodeWithTag("next-cue").assertDoesNotExist()
    }

    @Test fun searchesWithoutAReplyExpireInBothDirections() {
        for ((name, arrow) in listOf("Идёт Поиск" to "▶", "Станция не найдена в сценарии" to "◀")) {
            scenario(name)
            ui.onNodeWithTag("efir-status").assertTextEquals("идёт поиск…")
            ui.onNodeWithContentDescription("Поиск $arrow").assertIsNotEnabled()
            next()
            ui.onNodeWithTag("efir-status").assertTextEquals("Поиск не завершён")
            frequency("90,3")
        }
    }

    @Test fun focusEffectsKeepPlaybackRunning() {
        scenario("Потерян фокус")
        for (effect in listOf("Без звука", "Обычная громкость", "Тише", "Постоянная потеря")) {
            next()
            text(effect).assertExists()
            text("Стоп").assertIsEnabled()
        }
    }

    @Test fun selectionChangesOrderWithoutTuning() {
        scenario("Изменена Подборка")
        next()
        text("Подборка: Вторая").assertExists()
        text("1 · Орбита стенда").assertExists()
        frequency("90,3")
    }

    @Test fun profileSelectionAndDetachRemoveReceiverCapabilities() {
        scenario("Два профиля")
        text("Настройки").performClick()
        ui.onNodeWithContentDescription("Без Поиска").performClick()
        text("← Эфир").performClick()
        ui.onNodeWithContentDescription("Поиск ▶").assertDoesNotExist()
        ui.onNodeWithContentDescription("Подтверждённая Станция 90,3").assertDoesNotExist()
        next()
        text("Приёмник отключён").assertExists()
    }

    @Test fun motionClosesParkingAndDeveloperTools() {
        scenario("Движение и стоянка")
        text("Режим разработчика").performClick()
        text("Синтетическая диагностика").assertExists()
        next()
        frequency("90,3")
        text("Синтетическая диагностика").assertDoesNotExist()
        ui.onNodeWithContentDescription("Поиск ▶").assertDoesNotExist()
        ui.onNodeWithContentDescription("Стоянка").assertIsNotEnabled()
        next()
        ui.onNodeWithContentDescription("Стоянка").assertIsEnabled()
    }

    @Test fun bothThemesRenderTheSameConfirmedStation() {
        scenario("Светлая и тёмная Темы")
        text("Светлая").assertExists()
        frequency("90,3")
        checkImage("light")
        next()
        text("Тёмная").assertExists()
        frequency("90,3")
        checkImage("dark")
    }

    private fun checkImage(theme: String) {
        val actual = ui.onNodeWithTag("sandbox-radio").captureToImage().asAndroidBitmap()
        val directory = ui.activity.cacheDir
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("sandbox-$theme.png").use(BitmapFactory::decodeStream)
        actual.save(File(directory, "sandbox-$theme.png"))
        if (expected.sameAs(actual)) return
        actual.save(File(directory, "sandbox-$theme-actual.png"))
        val diff = Bitmap.createBitmap(maxOf(actual.width, expected.width),
            maxOf(actual.height, expected.height), Bitmap.Config.ARGB_8888)
        for (y in 0 until diff.height) for (x in 0 until diff.width) {
            if (x >= actual.width || y >= actual.height || x >= expected.width || y >= expected.height ||
                actual.getPixel(x, y) != expected.getPixel(x, y)) diff.setPixel(x, y, Color.RED)
        }
        diff.save(File(directory, "sandbox-$theme-diff.png"))
        throw AssertionError("Different $theme reference. See cache/sandbox-$theme{,-actual,-diff}.png")
    }

    private fun Bitmap.save(file: File) = file.outputStream().use {
        check(compress(Bitmap.CompressFormat.PNG, 100, it))
    }
}
