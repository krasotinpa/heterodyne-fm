package fm.heterodyne.app.test

import android.app.NotificationManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import fm.heterodyne.app.preflight.AlternateRadioActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlternateRadioUiTest {
    @get:Rule val ui = createAndroidComposeRule<AlternateRadioActivity>()

    @Test fun replacementRootTunesAndPlaybackSurvivesLeavingAndRecreatingTheActivity() {
        val notificationManager = ui.activity.getSystemService(NotificationManager::class.java)
        val scenario = ui.activityRule.scenario
        fun playingNotification() = notificationManager.activeNotifications.any {
            it.notification.channelId == "module-audio"
        }

        awaitText("90300 kHz")
        ui.onNodeWithTag("efir-topbar").assertDoesNotExist()
        ui.onNodeWithText("Tune to 97700 kHz").performClick()
        awaitText("97700 kHz")
        awaitText("Playing")
        ui.waitUntil(5_000) { playingNotification() }

        scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue("Playback notification disappeared in background", playingNotification())
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.recreate()
        awaitText("97700 kHz")
        awaitText("Playing")

        ui.onNodeWithText("Stop").performClick()
        awaitText("Stopped")
        ui.waitUntil(5_000) { !playingNotification() }
    }

    private fun awaitText(text: String) {
        ui.waitUntil(15_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
        ui.onNodeWithText(text).assertIsDisplayed()
    }
}
