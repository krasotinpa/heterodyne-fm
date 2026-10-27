package fm.heterodyne.app.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1600dp-h800dp-land")
class DiagnosticsScreenTest {

    @Test fun diagnosticsNamesSelectedDeviceKind() {
        val profiles = fm.heterodyne.app.receiver.ReceiverProfiles.Module.withProfile(
            fm.heterodyne.app.receiver.ReceiverProfile(
                fm.heterodyne.app.receiver.ReceiverKind("test"), "Тестовый приёмник", 1, 2) { true })
        val kind = mutableStateOf<fm.heterodyne.app.receiver.ReceiverKind?>(fm.heterodyne.app.receiver.ReceiverKind.Module)
        compose.setContent { DiagnosticsContent(true, false, false, false, null, {}, {}, kind.value, profiles::displayName) }
        compose.onNodeWithText("Модуль").assertIsDisplayed()
        compose.runOnIdle { kind.value = fm.heterodyne.app.receiver.ReceiverKind("test") }
        compose.onNodeWithText("Тестовый приёмник").assertIsDisplayed()
        compose.runOnIdle { kind.value = fm.heterodyne.app.receiver.ReceiverKind("unknown") }
        compose.onNodeWithText("unknown").assertIsDisplayed()
        compose.runOnIdle { kind.value = null }
        compose.onNodeWithText("Приёмник не выбран").assertIsDisplayed()
    }
    @get:Rule val compose = createComposeRule()

    @Test fun parkedSwitchChangesModeAndExportExplainsDownloads() {
        val enabled = mutableStateOf(false)
        var exports = 0
        compose.setContent {
            DiagnosticsContent(true, true, enabled.value, false, null,
                { enabled.value = it }, { exports++ })
        }
        compose.onNodeWithTag("diagnostics-version").assertIsDisplayed()
        compose.onNodeWithText("Связь с приёмником").assertIsDisplayed()
        compose.onNodeWithText("Приёмник подключён").assertIsDisplayed()
        compose.onNodeWithText("Журнал сохраняется в Download внутренней памяти.").assertIsDisplayed()
        compose.onNodeWithText("Скопируйте файл на SSD файловым менеджером.").assertIsDisplayed()
        compose.onNodeWithText("1. Отключите Модуль от USB.").assertDoesNotExist()
        compose.onNodeWithTag("developer-switch").assertIsOff()
            .assertHeightIsAtLeast(48.dp).performClick().assertIsOn()
        compose.onNodeWithText("Экспорт Журнала").performClick()
        assertEquals(1, exports)
        compose.onNodeWithTag("developer-switch").performClick().assertIsOff()
    }

    @Test fun drivingDisablesSwitchAndExport() {
        var changes = 0
        compose.setContent {
            DiagnosticsContent(false, false, false, false, null, { changes++ }, { changes++ })
        }
        compose.onNodeWithTag("developer-switch").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Экспорт Журнала").assertIsNotEnabled().performClick()
        assertEquals(0, changes)
    }

    @Test fun busyExportDisablesButtonAndShowsSavedPathAndSize() {
        val busy = mutableStateOf(true)
        val message = mutableStateOf<String?>(null)
        var exports = 0
        compose.setContent {
            DiagnosticsContent(true, true, false, busy.value, message.value, {}, { exports++ })
        }
        compose.onNodeWithText("Экспорт…").assertIsNotEnabled().performClick()
        assertEquals(0, exports)
        compose.runOnIdle {
            busy.value = false
            message.value = "Журнал сохранён: Download/heterodyne-diagnostic-123.log, 13 байт"
        }
        compose.onNodeWithText("Экспорт Журнала").assertIsEnabled()
        compose.onNodeWithText("Журнал сохранён: Download/heterodyne-diagnostic-123.log, 13 байт").assertIsDisplayed()
    }

    @Test fun badgeOnlyAppearsWhileEnabled() {
        val enabled = mutableStateOf(false)
        compose.setContent { DeveloperBadge(enabled.value) }
        compose.onNodeWithText("РАЗРАБОТЧИК").assertDoesNotExist()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("РАЗРАБОТЧИК").assertIsDisplayed()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("РАЗРАБОТЧИК").assertDoesNotExist()
    }
}
