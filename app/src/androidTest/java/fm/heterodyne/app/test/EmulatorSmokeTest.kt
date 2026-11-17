package fm.heterodyne.app.test

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fm.heterodyne.app.MainActivity
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorSmokeTest {
    companion object {
        @BeforeClass @JvmStatic fun grantVirtualLocationPermission() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val pkg = instrumentation.targetContext.packageName
            for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
                instrumentation.uiAutomation.grantRuntimePermission(pkg, permission)
            }
        }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainScreenSurvivesActivityRecreation() {
        compose.onNodeWithTag("efir-topbar").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("efir-topbar").assertIsDisplayed()
    }
}
