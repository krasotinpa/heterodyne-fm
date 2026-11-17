package fm.heterodyne.app.test

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fm.heterodyne.app.MainActivity
import fm.heterodyne.app.audio.AudioState
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.preflight.PreflightApplication
import fm.heterodyne.app.stations.StationEntry
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreflightReceiverSmokeTest {
    companion object {
        @BeforeClass @JvmStatic fun grantVirtualLocationPermission() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
                instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
            }
        }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun manifestReceiverTunesAndStreamsSyntheticPcmThroughAudioTrack() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PreflightApplication
        assertEquals("fm.heterodyne.app.preflight", app.packageName)
        compose.waitUntil(10000) { app.module.moduleState == ConnectionState.Observing && app.module.transition.confirmed != null }
        try {
            compose.runOnIdle { app.module.pickStation(StationEntry(97700)) }
            compose.waitUntil(10000) {
                app.module.transition.confirmed?.frequencyKhz == 97700 &&
                    app.module.audioCounters.state == AudioState.Playing && app.module.audioCounters.bytes > 96000
            }
            assertEquals(0L, app.module.audioCounters.badPackets)
        } finally {
            compose.runOnIdle { app.module.userStop() }
        }
        compose.waitUntil(5000) { app.module.audioState == AudioState.Stopped }
    }
}
