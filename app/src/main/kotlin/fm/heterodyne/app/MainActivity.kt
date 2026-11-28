package fm.heterodyne.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import fm.heterodyne.ui.compose.RadioContent
import fm.heterodyne.ui.contract.RadioUi

open class MainActivity : ComponentActivity() {
    private val stations get() = application as StationsApplication
    private val drivingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val drivingMonitor: fm.heterodyne.app.host.DrivingMonitor by lazy {
        val stations = application as StationsApplication
        fm.heterodyne.app.host.DrivingMonitor(
            fm.heterodyne.app.host.AndroidDrivingSpeedSource(this, drivingHandler) { drivingMonitor.poll() },
            android.os.SystemClock::elapsedRealtime,
            stations.module::updateDriving,
            stations.diagnostics::drivingEvent,
        )
    }
    private val drivingTick = object : Runnable {
        override fun run() {
            drivingMonitor.poll()
            drivingHandler.postDelayed(this, 1000)
        }
    }

    override fun onStart() {
        super.onStart()
        stations.radio.foreground(true)
        drivingMonitor.start()
        drivingHandler.post(drivingTick)
    }

    override fun onStop() {
        stations.radio.foreground(false)
        drivingHandler.removeCallbacks(drivingTick)
        drivingMonitor.stop()
        super.onStop()
    }

    private val locationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> drivingMonitor.poll() }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        stations.diagnostics.updateProbeUiMode(newConfig.uiMode)
        stations.radio.publish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stations = application as StationsApplication
        stations.module.openApplication(restoredActivity = savedInstanceState != null)
        stations.diagnostics.updateProbeUiMode(resources.configuration.uiMode)
        val permissionPreferences = getSharedPreferences("diagnostics", MODE_PRIVATE)
        val probePermission = fm.heterodyne.app.host.ProbePermission(
            permissionPreferences.getBoolean("probe-location-requested", false),
            { permissionPreferences.edit().putBoolean("probe-location-requested", true).apply() },
            {
                // Android 12 can ignore a FINE-only request. GPS still requires FINE if only COARSE is granted.
                locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION))
            },
        )
        probePermission.update(true,
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
        setContent { RenderRadio(stations.radio, onToolsClosed = stations.radio::closeTools) }
    }

    @Composable
    protected open fun RenderRadio(radio: RadioUi, onToolsClosed: () -> Unit) {
        RadioContent(radio, onToolsClosed = onToolsClosed)
    }
}
