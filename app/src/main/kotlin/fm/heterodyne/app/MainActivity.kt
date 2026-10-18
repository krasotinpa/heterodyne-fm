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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stations = application as StationsApplication
        setContent {
            val appearance = remember {
                fm.heterodyne.app.ui.AppearancePreferences(getSharedPreferences("appearance", MODE_PRIVATE))
            }
            fm.heterodyne.app.ui.HeterodyneTheme(appearance.theme, appearance.palette) {
                val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
                ) { uri -> stations.diagnostics.finishExport(uri) }
                var parked by rememberSaveable { mutableStateOf(false) }
                val driving = stations.module.driving
                androidx.compose.runtime.LaunchedEffect(driving) { if (driving) parked = false }
                if (!parked || driving) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        fm.heterodyne.app.ui.EfirScreen(
                            stations.session.screen, stations.module.transition,
                            connected = stations.module.moduleState == fm.heterodyne.app.module.ConnectionState.Observing,
                            playing = stations.module.audioState in listOf(fm.heterodyne.app.audio.AudioState.Starting, fm.heterodyne.app.audio.AudioState.Playing),
                            driving = driving,
                            actions = fm.heterodyne.app.ui.EfirActions(
                                select = stations.module::pickStation, station = stations.module::nextStation,
                                step = stations.module::step, seek = stations.module::seek, band = stations.module::changeBand,
                                activate = stations.module::activateSelection, parked = { if (!stations.module.driving) parked = true },
                                check = stations.module::checkConnection,
                                play = {
                                    if (stations.module.audioState in listOf(fm.heterodyne.app.audio.AudioState.Starting, fm.heterodyne.app.audio.AudioState.Playing))
                                        stations.module.stopAudio() else stations.module.startAudio()
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
                                fm.heterodyne.app.module.Band.valueOf(it.band.name), it.frequencyKhz)
                    },
                    fm.heterodyne.app.ui.ParkingActions(
                        region = stations.module::selectRegion, activate = stations.module::activateSelection,
                        create = stations.session::createSelection, rename = stations.session::renameSelection,
                        delete = stations.session::deleteSelection, add = stations.session::addStation,
                        remove = stations.session::removeStation, move = stations.session::moveStation,
                    ), back = { parked = false },
                    theme = appearance.theme, palette = appearance.palette,
                    selectTheme = appearance::selectTheme, selectPalette = appearance::selectPalette,
                    connected = stations.module.moduleState == fm.heterodyne.app.module.ConnectionState.Observing,
                    developerEnabled = stations.diagnostics.developerMode.enabled,
                    developerTools = { fm.heterodyne.app.ui.DeveloperToolsScreen(stations.diagnostics, parked && !driving) },
                ) { _ ->
                    fm.heterodyne.app.ui.DiagnosticsScreen(stations.diagnostics, parked && !driving,
                        connected = stations.module.moduleState == fm.heterodyne.app.module.ConnectionState.Observing, export = {
                        stations.diagnostics.beginExport {
                            try { exportLauncher.launch("heterodyne-diagnostic.log") }
                            catch (_: Exception) { stations.diagnostics.finishExport(null) }
                        }
                    })
                }
            }
        }
    }
}
