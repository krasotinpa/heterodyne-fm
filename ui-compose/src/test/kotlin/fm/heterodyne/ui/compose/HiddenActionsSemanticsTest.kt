package fm.heterodyne.ui.compose

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w1280dp-h720dp-land-mdpi")
class HiddenActionsSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun seekActionsLeaveAccessibilityWhileMovingOrUnsupported() {
        val stopped = sampleRadio(tuning = tuning(confirmed = station(97700)))
        val state = mutableStateOf(stopped)
        compose.setContent { RadioSnapshot(state.value, {}) }

        val seekAction = hasAnyAncestor(hasTestTag("seek-group")) and hasClickAction()
        compose.onAllNodes(seekAction).assertCountEquals(2)
        compose.onNodeWithContentDescription("Поиск ◀").assertIsEnabled()
        compose.onNodeWithContentDescription("Поиск ▶").assertIsEnabled()

        compose.runOnIdle { state.value = stopped.copy(moving = true) }
        compose.onAllNodes(seekAction).assertCountEquals(0)
        compose.onNodeWithContentDescription("Стоянка").assertIsNotEnabled()

        compose.runOnIdle { state.value = stopped.copy(receiver = stopped.receiver.copy(features = emptySet())) }
        compose.onAllNodes(seekAction, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun developerToolsLeaveSemanticsWhenDeveloperModeIsOff() {
        val enabled = parking(diagnostics = sampleDiagnostics.copy(developerEnabled = true))
        val state = mutableStateOf(enabled)
        compose.setContent { RadioSnapshot(state.value, {}) }

        compose.onNodeWithText("Режим разработчика").assertIsEnabled()
        compose.runOnIdle { state.value = enabled.copy(diagnostics = sampleDiagnostics) }
        compose.onNodeWithText("Режим разработчика").assertDoesNotExist()
        compose.onNodeWithText("Режим разработчика", useUnmergedTree = true).assertDoesNotExist()
    }
}
