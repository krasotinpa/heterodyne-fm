package fm.heterodyne.app.test

import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fm.heterodyne.app.BuildConfig
import fm.heterodyne.app.MainActivity
import fm.heterodyne.app.audio.AudioState
import fm.heterodyne.app.preflight.PreflightApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Phases are selected explicitly by preflight.py, with ordinary process restarts between them. */
@RunWith(AndroidJUnit4::class)
class Run4UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as PreflightApplication
    private val selectionName = "Проверка 4"
    private fun note(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
    private fun waitFor(test: () -> Boolean) = compose.waitUntil(15000, test)
    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction() and !hasSetTextAction()).performClick()
    private fun setting(text: String) = compose.onNodeWithText(text).performScrollTo()
    private fun parked() {
        waitFor { app.session.screen.state != null }
        compose.onNodeWithContentDescription("Стоянка").performClick()
    }
    private fun verifyEntry() {
        compose.onNodeWithText("Подборка: $selectionName").assertIsDisplayed()
        compose.onNodeWithTag("station:90500").assertIsDisplayed()
        compose.onNodeWithText("Тест FM 2 · 90500 кГц").assertIsDisplayed()
        assertEquals(1, app.session.activeEntries.size)
    }

    @Test fun visualScreens() {
        val args = InstrumentationRegistry.getArguments()
        val theme = requireNotNull(args.getString("theme"))
        require(theme in setOf("Light", "Dark"))
        val configuration = compose.activity.resources.configuration
        assertEquals(args.getString("font")!!.toFloat(), configuration.fontScale, 0.01f)
        assertEquals(160, configuration.densityDpi)
        val automation = instrumentation.uiAutomation
        fun shell(command: String) {
            val result = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("$command && echo OK")).use { it.bufferedReader().readText() }
            assertEquals(command, "OK", result.trim())
        }
        val case = requireNotNull(args.getString("case"))
        require(case.matches(Regex("[0-9]+x[0-9]+-1\\.[03]-(light|dark)")))
        val folder = "/data/local/tmp/run4-visual-$case"
        shell("mkdir $folder")
        fun snap(screen: String) {
            compose.waitForIdle()
            // Capture the compositor through Android's shell screencap while this screen is open.
            shell("screencap -p $folder/$screen.png")
        }
        fun activate(text: String) =
            compose.onNode(hasText(text) and hasClickAction() and !hasSetTextAction())
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

        parked()
        click("Настройки")
        setting(if (theme == "Light") "Светлая" else "Тёмная").performClick().assertIsSelected()
        assertEquals(theme, app.getSharedPreferences("appearance", 0).getString("theme", null))
        click("← Эфир")
        waitFor { app.module.transition.confirmed != null }
        snap("efir")
        parked()
        compose.onNodeWithTag("parking-menu").assertIsDisplayed()
        snap("parking")
        click("Настройки")
        compose.onNodeWithText("Запуск").assertIsDisplayed()
        snap("settings")
        setting("Регион: Москва ›").performClick()
        compose.onNodeWithText("Москва ✓").assertIsDisplayed()
        snap("regions")
        click("Подборки")
        compose.onNodeWithText("Справочник →").assertIsDisplayed()
        snap("stations")
        click("Добавить Станцию")
        compose.onNodeWithTag("station-name").performTextInput("Проверка")
        compose.onNodeWithTag("station-frequency").performTextInput("200,0")
        // Invoke the action without moving the viewport, so clipping remains visible in the PNG.
        activate("Сохранить Станцию")
        compose.onNodeWithText("Введите FM-частоту 87,5–108,0 МГц с шагом 0,1").assertExists()
        snap("station-error")
        click("Настройки")
        assertTrue(app.getSharedPreferences("preflight", 0).edit().putString("catalog", "failure").commit())
        updateCatalog()
        setting("Не удалось обновить Справочник").assertIsDisplayed()
        snap("catalog-error")
        click("Диагностика")
        compose.onNodeWithTag("diagnostics-version").assertIsDisplayed()
        snap("diagnostics")
        click("О приложении")
        compose.onNodeWithText("Версия ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        snap("about")
    }

    @Test fun prepareData() {
        parked()
        click("О приложении")
        compose.onNodeWithText("Версия ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        assertFalse(BuildConfig.VERSION_NAME.contains("dirty"))
        click("Настройки")
        setting("Тёмная").performClick()
        setting("Тон").performClick()
        setting("20 с").performClick()
        setting("Играть последнюю Станцию при запуске").assertIsSelected()
        setting("Регион: Москва ›").performClick()
        compose.onNodeWithText("Москва ✓").assertIsDisplayed()
        compose.onNodeWithText("Ярославль").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Москва ✓").getUnclippedBoundsInRoot().top <
            compose.onNodeWithText("Ярославль").getUnclippedBoundsInRoot().top)
        click("Ярославль")
        waitFor { app.session.screen.state?.selectedRegion == "yaroslavl" }
        setting("Регион: Ярославль ›").performClick()
        click("Москва")
        waitFor { app.session.screen.state?.selectedRegion == "moskva" }
        click("Подборки")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(selectionName)
        click("Создать")
        waitFor { app.session.screen.state?.regionSelections("moskva")?.selections?.any { it.name == selectionName } == true }
        click(selectionName)
        compose.onNodeWithText("Сделать активной").performScrollTo().performClick()
        waitFor { app.session.screen.state?.regionSelections("moskva")?.activeName == selectionName }
        click("Добавить Станцию")
        compose.onNodeWithTag("station-name").performTextInput("Тест FM")
        compose.onNodeWithTag("station-frequency").performTextInput("90,5")
        click("Сохранить Станцию")
        waitFor { app.session.activeEntries.singleOrNull()?.frequencyKhz == 90500 }
        compose.onNodeWithTag("station:90500").performClick()
        compose.onNodeWithTag("station-frequency").assertIsNotEnabled()
        compose.onNodeWithTag("station-name").performTextReplacement("Тест FM 2")
        click("Сохранить Станцию")
        waitFor { app.session.activeEntries.singleOrNull()?.name == "Тест FM 2" }
        click("Добавить Станцию")
        compose.onNodeWithTag("station-name").performTextInput("Ошибка FM")
        compose.onNodeWithTag("station-frequency").performTextInput("200,0")
        click("Сохранить Станцию")
        compose.onNodeWithText("Введите FM-частоту 87,5–108,0 МГц с шагом 0,1").assertIsDisplayed()
        click("Отмена")
        verifyEntry()
        // Explicit Stop persists lastPlayed=false before the host starts a fresh process.
        click("← Эфир")
        click("Старт")
        waitFor { app.module.audioState == AudioState.Playing }
        click("Стоп")
        waitFor { app.module.audioState == AudioState.Stopped }
        // The public callback queues behind pending storage writes; no private-file polling.
        val stored = java.util.concurrent.atomic.AtomicBoolean(false)
        app.session.readLaunchStation { stored.set(true) }
        waitFor { stored.get() }
        note("PREPARE_PID=${android.os.Process.myPid()}")
        note("A/B prepared: version, settings, regions, create/edit/invalid station, explicit Stop")
    }

    @Test fun verifyPersistence() {
        parked()
        click("Настройки")
        setting("Тёмная").assertIsSelected()
        setting("Тон").assertIsSelected()
        setting("20 с").assertIsSelected()
        setting("Играть последнюю Станцию при запуске").assertIsSelected()
        setting("Регион: Москва ›").assertIsDisplayed()
        click("Подборки")
        verifyEntry()
        assertEquals(AudioState.Stopped, app.module.audioState)
        note("B6 passed in new process: settings, active selection, renamed 90500, stopped")
    }

    private fun updateCatalog(timeout: Long = 15000) {
        setting("Обновить Справочник").performClick()
        val queued = java.util.concurrent.atomic.AtomicBoolean(false)
        app.session.readLaunchStation { queued.set(true) }
        waitFor { queued.get() }
        compose.waitForIdle()
        compose.waitUntil(timeout) { app.session.screen.catalogUpdateResult != null && !app.session.screen.catalogUpdateBusy }
    }

    @Test fun realHttps() {
        assertTrue(app.getSharedPreferences("preflight", 0).edit().putString("catalog", "https").commit())
        parked()
        click("Настройки")
        updateCatalog(150000)
        val result = app.session.screen.catalogUpdateResult!!
        note("REAL_HTTPS outcome=$result")
        assertTrue("Actual HTTPS failed: $result", result == "Нет обновлений" || result.startsWith("Справочник обновлён: Ревизия "))
        // A repeated successful real request must not reinstall the same release.
        updateCatalog(150000)
        setting("Нет обновлений").assertIsDisplayed()
        click("Подборки")
        verifyEntry()
    }

    @Test fun catalogFixtures() {
        parked()
        click("Настройки")
        assertTrue(app.getSharedPreferences("preflight", 0).edit().putString("catalog", "failure").commit())
        updateCatalog()
        setting("Не удалось обновить Справочник").assertIsDisplayed()
        setting("Регион: Москва ›").performClick()
        compose.onNodeWithText("Москва ✓").assertIsDisplayed()
        compose.onNodeWithText("Ярославль").assertIsDisplayed()
        click("Москва ✓")
        click("Подборки")
        verifyEntry()
        click("Настройки")
        assertTrue(app.getSharedPreferences("preflight", 0).edit().putString("catalog", "fixture").commit())
        updateCatalog()
        setting("Справочник обновлён: Ревизия 9999-01-01").assertIsDisplayed()
        updateCatalog()
        assertEquals("Нет обновлений", app.session.screen.catalogUpdateResult)
        setting("Нет обновлений").assertIsDisplayed()
        click("Подборки")
        verifyEntry()
        note("CATALOG_FIXTURES passed: synthetic failure, atomic install 9999-01-01, no update, selection unchanged")
    }

    @Test fun verifyCatalogAndExport() {
        parked()
        click("Подборки")
        verifyEntry()
        click("Настройки")
        assertTrue(app.getSharedPreferences("preflight", 0).edit().putString("catalog", "fixture").commit())
        updateCatalog()
        assertEquals("Нет обновлений", app.session.screen.catalogUpdateResult)
        click("Диагностика")
        compose.onNodeWithTag("developer-switch").performClick()
        compose.onNodeWithText("Режим разработчика: Вкл").assertIsDisplayed()
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun names(): Set<String> = app.contentResolver.query(downloads, arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            null, null, null)!!.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val exported = mutableSetOf<String>()
        repeat(2) {
            val before = names()
            click("Экспорт Журнала")
            waitFor { !app.diagnostics.exportBusy && app.diagnostics.diagnosticMessage != null }
            val message = app.diagnostics.diagnosticMessage!!
            assertTrue(message, message.startsWith("Журнал сохранён: Download/"))
            val added = names() - before
            assertEquals(1, added.size)
            val name = added.single()
            assertTrue(exported.add(name))
            app.contentResolver.query(downloads, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE,
                MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.RELATIVE_PATH),
                "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(name), null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(2))
                assertEquals("Download/", cursor.getString(3))
                val bytes = app.contentResolver.openInputStream(ContentUris.withAppendedId(downloads, cursor.getLong(0)))!!.use { it.readBytes() }
                assertEquals(cursor.getLong(1), bytes.size.toLong())
                assertTrue(message.contains("${bytes.size} байт"))
                assertTrue(bytes.toString(Charsets.UTF_8).contains("PREFLIGHT"))
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                note("MEDIASTORE name=$name bytes=${bytes.size} sha256=$sha pending=0 path=Download/")
                assertFalse(cursor.moveToNext())
            }
        }
        note("X passed: two unique published MediaStore exports, bytes readable, UI size matches")
    }
}
