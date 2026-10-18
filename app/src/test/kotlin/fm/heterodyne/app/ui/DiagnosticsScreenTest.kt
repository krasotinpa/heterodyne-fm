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
    @get:Rule val compose = createComposeRule()

    @Test fun parkedSwitchChangesModeAndExportExplainsSharedUsb() {
        val enabled = mutableStateOf(false)
        var exports = 0
        compose.setContent {
            DiagnosticsContent(true, true, enabled.value, false, null,
                { enabled.value = it }, { exports++ })
        }
        compose.onNodeWithTag("diagnostics-version").assertIsDisplayed()
        compose.onNodeWithText("Связь с приёмником").assertIsDisplayed()
        compose.onNodeWithText("Приёмник подключён").assertIsDisplayed()
        compose.onNodeWithText("1. Отключите Модуль от USB.").assertIsDisplayed()
        compose.onNodeWithText("2. Подключите SSD к общему USB-разъёму.").assertIsDisplayed()
        compose.onNodeWithText("3. После экспорта верните Модуль.").assertIsDisplayed()
        compose.onNodeWithTag("developer-switch").assertIsOff()
            .assertHeightIsAtLeast(48.dp).performClick().assertIsOn()
        compose.onNodeWithText("Экспорт Журнала на SSD").performClick()
        assertEquals(1, exports)
        compose.onNodeWithTag("developer-switch").performClick().assertIsOff()
    }

    @Test fun drivingDisablesSwitchAndExport() {
        var changes = 0
        compose.setContent {
            DiagnosticsContent(false, false, false, false, null, { changes++ }, { changes++ })
        }
        compose.onNodeWithTag("developer-switch").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Экспорт Журнала на SSD").assertIsNotEnabled().performClick()
        assertEquals(0, changes)
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
