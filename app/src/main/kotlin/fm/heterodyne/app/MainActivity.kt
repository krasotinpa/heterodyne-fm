package fm.heterodyne.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.heterodyne.ui.contract.*
import fm.heterodyne.app.ui.*
import fm.heterodyne.app.stations.StationEntry
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
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
        setContent { LegacyContent(stations) }
    }
}

/** Temporary binding for the existing screens until the UI module is extracted (#324). */
@androidx.compose.runtime.Composable
private fun LegacyContent(app: StationsApplication) {
    val state by app.radio.state.collectAsStateWithLifecycle()
    val send = app.radio::submit
    val settings = state.settings
    val connected = state.receiver.connection == Connection.Observing
    val playing = state.playback.status in listOf(PlaybackStatus.Starting, PlaybackStatus.Playing)
    val region = state.stations.activeRegionId
    fun activate(name: String) { region?.let { send(RadioAction.ActivateSelection(it, name)) } }
    fun edit(change: SelectionEdit) { send(RadioAction.Edit(change)) }
    fun setting(change: SettingChange) { send(RadioAction.ChangeSetting(change)) }
    val theme = fm.heterodyne.app.host.ThemeChoice.valueOf(settings.theme.name)
    val palette = fm.heterodyne.app.host.Palette.valueOf(settings.palette.name)
    val marker = fm.heterodyne.app.host.ScaleMarker.valueOf(settings.scaleMarker.name)
    HeterodyneTheme(theme, palette) {
        if (state.mode == ScreenMode.Efir) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                EfirScreen(app.session.screen, app.module.transition, connectionState = app.module.moduleState,
                    connected = connected, playing = playing, driving = state.moving,
                    capabilities = app.module.capabilities, scaleMarker = marker,
                    parkShowLabel = settings.parkShowLabel, parkLargeIcon = settings.parkLargeIcon,
                    noFocus = state.playback.focus == FocusEffect.AutostartDenied, seekHint = app.module.seekHint,
                    actions = EfirActions(
                        select = { entry ->
                            val selection = state.stations.activeSelectionName
                            if (region != null && selection != null)
                                send(RadioAction.SelectStation(region, StationRef.Selection(selection, entry.frequencyKhz)))
                        }, selectCatalog = { entry ->
                            if (region != null) entry.catalogId?.let { send(RadioAction.SelectStation(region, StationRef.Catalog(it))) }
                        }, station = { send(RadioAction.MoveInSelection(enumValueOf(it.name))) },
                        step = { send(RadioAction.Step(enumValueOf(it.name))) },
                        seek = { send(RadioAction.Seek(enumValueOf(it.name))) },
                        activate = ::activate, parked = { send(RadioAction.EnterParking) },
                        check = { send(RadioAction.CheckConnection) },
                        play = { send(if (playing) RadioAction.Stop else RadioAction.Start) },
                        permission = { send(RadioAction.RetryPermission) },
                    ))
                DeveloperBadge(state.diagnostics.developerEnabled, Modifier.align(androidx.compose.ui.Alignment.BottomEnd))
            }
        } else {
            ParkingScreen(app.session.screen, state.moving,
                state.tuning.confirmed?.let { StationEntry(it.frequencyKhz) },
                ParkingActions(
                    region = { send(RadioAction.SelectRegion(it)) }, activate = ::activate,
                    create = { id, name -> edit(SelectionEdit.Create(SelectionRef(id, name))) },
                    rename = { id, name, next -> edit(SelectionEdit.Rename(SelectionRef(id, name), next)) },
                    delete = { id, name -> edit(SelectionEdit.Delete(SelectionRef(id, name))) },
                    add = { id, name, entry -> edit(SelectionEdit.Add(SelectionRef(id, name), entry.view(), entry.catalogId)) },
                    remove = { id, name, entry -> edit(SelectionEdit.Remove(SelectionRef(id, name), entry.frequencyKhz)) },
                    move = { id, name, entry, to -> edit(SelectionEdit.Move(SelectionRef(id, name), entry.frequencyKhz, to)) },
                    editStation = { id, name, entry, new -> edit(SelectionEdit.EditName(SelectionRef(id, name), entry.frequencyKhz, new)) },
                    clearNameError = { edit(SelectionEdit.ClearIssue) }, deleteRegion = { send(RadioAction.DeleteObsoleteRegion(it)) },
                    updateCatalog = { send(RadioAction.UpdateCatalog) },
                ), back = { send(RadioAction.ExitParking) }, theme = theme, palette = palette,
                selectTheme = { setting(SettingChange.Theme(enumValueOf(it.name))) },
                selectPalette = { setting(SettingChange.PaletteChoice(enumValueOf(it.name))) },
                receiverName = app.module::receiverName, receiverKinds = app.module.receiverKinds,
                selectedReceiver = app.module.selectedReceiver, selectReceiver = { setting(SettingChange.Receiver(ReceiverId(it.value))) },
                signalMethod = enumValueOf(settings.transitionSignal?.name ?: "Off"),
                selectSignal = { setting(SettingChange.TransitionSignal(enumValueOf(it.name))) },
                scaleMarker = marker, selectScaleMarker = { setting(SettingChange.Marker(enumValueOf(it.name))) },
                parkShowLabel = settings.parkShowLabel, selectParkShowLabel = { setting(SettingChange.ParkLabel(it)) },
                parkLargeIcon = settings.parkLargeIcon, selectParkLargeIcon = { setting(SettingChange.ParkIcon(it)) },
                seekLimitSeconds = settings.seekLimitSeconds ?: 20, selectSeekLimit = { setting(SettingChange.SeekLimit(it)) },
                autostartEnabled = settings.autostart, selectAutostart = { setting(SettingChange.Autostart(it)) },
                connected = connected, developerEnabled = state.diagnostics.developerEnabled,
                developerTools = {
                    androidx.compose.runtime.DisposableEffect(app.radio) { onDispose(app.radio::closeTools) }
                    DeveloperToolsScreen(state.diagnostics, send)
                },
            ) { _ ->
                val export = state.diagnostics.export
                val message = when (export) {
                    is ExportState.Exported -> "Журнал сохранён: ${export.path}, ${export.sizeBytes} байт"
                    ExportState.Failed -> "Не удалось записать Журнал"
                    else -> null
                }
                DiagnosticsContent(true, connected, state.diagnostics.developerEnabled, export == ExportState.InProgress,
                    message, { send(RadioAction.SetDeveloper(it)) }, { send(RadioAction.ExportJournal) },
                    app.module.selectedReceiver, app.module::receiverName)
            }
        }
    }
}
