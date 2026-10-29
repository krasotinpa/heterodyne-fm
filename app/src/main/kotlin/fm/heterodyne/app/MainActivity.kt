package fm.heterodyne.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val drivingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val drivingMonitor: fm.heterodyne.app.ui.DrivingMonitor by lazy {
        val stations = application as StationsApplication
        fm.heterodyne.app.ui.DrivingMonitor(
            fm.heterodyne.app.ui.AndroidDrivingSpeedSource(this, drivingHandler) { drivingMonitor.poll() },
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
        drivingMonitor.start()
        drivingHandler.post(drivingTick)
    }

    override fun onStop() {
        drivingHandler.removeCallbacks(drivingTick)
        drivingMonitor.stop()
        super.onStop()
    }

    private val locationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> drivingMonitor.poll() }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        (application as StationsApplication).diagnostics.updateProbeUiMode(newConfig.uiMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stations = application as StationsApplication
        stations.module.openApplication(restoredActivity = savedInstanceState != null)
        stations.diagnostics.updateProbeUiMode(resources.configuration.uiMode)
        val permissionPreferences = getSharedPreferences("diagnostics", MODE_PRIVATE)
        val probePermission = fm.heterodyne.app.ui.ProbePermission(
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
        setContent {
            val signalPreferences = remember {
                fm.heterodyne.app.ui.TransitionSignalPreferences(getSharedPreferences("transition-signal", MODE_PRIVATE))
            }
            val scaleMarkerPreferences = remember {
                fm.heterodyne.app.ui.ScaleMarkerPreferences(getSharedPreferences("scale-marker", MODE_PRIVATE))
            }
            val parkPreferences = remember {
                fm.heterodyne.app.ui.ParkButtonPreferences(getSharedPreferences("park-button", MODE_PRIVATE))
            }
            val seekPreferences = remember {
                fm.heterodyne.app.ui.SeekPreferences(getSharedPreferences("seek", MODE_PRIVATE))
            }
            val autostartPreferences = remember {
                fm.heterodyne.app.ui.AutostartPreferences(getSharedPreferences("autostart", MODE_PRIVATE))
            }
            val appearance = remember {
                fm.heterodyne.app.ui.AppearancePreferences(getSharedPreferences("appearance", MODE_PRIVATE))
            }
            fm.heterodyne.app.ui.HeterodyneTheme(appearance.theme, appearance.palette) {
                var parked by rememberSaveable { mutableStateOf(false) }
                val driving = stations.module.driving
                androidx.compose.runtime.SideEffect { stations.module.updateParked(parked && !driving) }
                androidx.compose.runtime.LaunchedEffect(driving) { if (driving) parked = false }
                if (!parked || driving) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        fm.heterodyne.app.ui.EfirScreen(
                            stations.session.screen, stations.module.transition,
                            connectionState = stations.module.moduleState,
                            connected = stations.module.moduleState == fm.heterodyne.app.module.ConnectionState.Observing,
                            playing = stations.module.audioState in listOf(fm.heterodyne.app.audio.AudioState.Starting, fm.heterodyne.app.audio.AudioState.Playing),
                            driving = driving,
                            capabilities = stations.module.capabilities,
                            scaleMarker = scaleMarkerPreferences.marker,
                            parkShowLabel = parkPreferences.showLabel, parkLargeIcon = parkPreferences.largeIcon,
                            noFocus = stations.module.noFocus,
                            seekHint = stations.module.seekHint,
                            actions = fm.heterodyne.app.ui.EfirActions(
                                select = stations.module::pickStation, station = stations.module::nextStation,
                                step = stations.module::step, seek = stations.module::seek,
                                activate = stations.module::activateSelection, parked = { if (!stations.module.driving) parked = true },
                                check = stations.module::checkConnection,
                                play = {
                                    if (stations.module.audioState in listOf(fm.heterodyne.app.audio.AudioState.Starting, fm.heterodyne.app.audio.AudioState.Playing))
                                        stations.module.userStop() else stations.module.startAudio()
                                },
                                permission = stations.module::retryModulePermission,
                            ),
                        )
                        fm.heterodyne.app.ui.DeveloperBadge(stations.diagnostics.developerMode.enabled,
                            Modifier.align(androidx.compose.ui.Alignment.BottomEnd))
                    }
                    return@HeterodyneTheme
                }
                fm.heterodyne.app.ui.ParkingScreen(
                    stations.session.screen, driving,
                    stations.module.currentStation?.takeIf {
                        stations.module.moduleState == fm.heterodyne.app.module.ConnectionState.Observing &&
                            fm.heterodyne.app.module.isSelectableFrequency(
                                fm.heterodyne.app.module.Band.FM, it.frequencyKhz)
                    },
                    fm.heterodyne.app.ui.ParkingActions(
                        region = stations.module::selectRegion, activate = stations.module::activateSelection,
                        create = stations.session::createSelection, rename = stations.session::renameSelection,
                        delete = stations.session::deleteSelection, add = stations.session::addStation,
                        remove = stations.session::removeStation, move = stations.session::moveStation,
                        clearNameError = stations.session::clearNameError,
                    ), back = { parked = false },
                    receiverName = stations.module::receiverName, receiverKinds = stations.module.receiverKinds, selectedReceiver = stations.module.selectedReceiver,
                    selectReceiver = stations.module::selectReceiver,
                    theme = appearance.theme, palette = appearance.palette,
                    signalMethod = signalPreferences.method, selectSignal = signalPreferences::selectMethod,
                    scaleMarker = scaleMarkerPreferences.marker, selectScaleMarker = scaleMarkerPreferences::selectMarker,
                    parkShowLabel = parkPreferences.showLabel, selectParkShowLabel = parkPreferences::selectShowLabel,
                    parkLargeIcon = parkPreferences.largeIcon, selectParkLargeIcon = parkPreferences::selectLargeIcon,
                    seekLimitSeconds = seekPreferences.seconds, selectSeekLimit = seekPreferences::selectSeconds,
                    autostartEnabled = autostartPreferences.enabled, selectAutostart = autostartPreferences::selectEnabled,
                    selectTheme = appearance::selectTheme, selectPalette = appearance::selectPalette,
                    connected = stations.module.moduleState == fm.heterodyne.app.module.ConnectionState.Observing,
                    developerEnabled = stations.diagnostics.developerMode.enabled,
                    developerTools = { fm.heterodyne.app.ui.DeveloperToolsScreen(stations.diagnostics, parked && !driving) },
                ) { _ ->
                    fm.heterodyne.app.ui.DiagnosticsScreen(stations.diagnostics, parked && !driving,
                        connected = stations.module.moduleState == fm.heterodyne.app.module.ConnectionState.Observing, export = stations.diagnostics::exportJournal,
                        receiverKind = stations.module.selectedReceiver, receiverName = stations.module::receiverName)
                }
            }
        }
    }
}
