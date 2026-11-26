package fm.heterodyne.ui.compose

import fm.heterodyne.ui.contract.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

internal fun station(khz: Int, name: String? = null) = StationView(khz, name)
internal fun pause(kind: PauseKind, direction: Direction, millis: Long) =
    CommandPauseView(kind, direction, millis, TestTimeSource().markNow() + millis.milliseconds)
internal fun tuning(phase: TuningPhase = TuningPhase.Idle, targetKhz: Int? = null,
                    confirmed: StationView? = null, pause: CommandPauseView? = null, saved: StationView? = null) =
    TuningView(confirmed, targetKhz, saved, phase, pause, null)

internal fun stationsView(entries: List<StationView> = emptyList(), catalog: List<CatalogStationView> = emptyList()) =
    StationsView(true, if (catalog.isEmpty()) emptyList() else listOf(RegionView("moskva", "Москва", false)),
        "moskva", listOf(SelectionView("Основной", entries)), "Основной", catalog, null, null, null, CatalogUpdate.Idle)

internal val sampleSettings = SettingsView(ThemeChoice.Auto, ResolvedTheme.Dark, Palette.Coral,
    true, false, ScaleMarker.Pin, true, 20, SignalChoice.KeyClick)
internal val moduleOption = ReceiverOption(ReceiverId("module"), "Модуль")
internal val testOption = ReceiverOption(ReceiverId("test"), "Тестовый приёмник")
internal val sampleDiagnostics = DiagnosticsView("test-version", false, ExportState.Idle, null)

internal fun sampleRadio(
    stations: StationsView = stationsView(), tuning: TuningView = tuning(), connected: Boolean = true,
    driving: Boolean = false, features: Set<Feature> = setOf(Feature.Seek), seekHint: Int? = null,
    connectionState: Connection = if (connected) Connection.Observing else Connection.Disconnected,
    playing: Boolean = false, noFocus: Boolean = false, parkShowLabel: Boolean = true, parkLargeIcon: Boolean = false,
): RadioUiState {
    val empty = stations.selections.firstOrNull { it.name == stations.activeSelectionName }?.entries.isNullOrEmpty()
    val reason = when {
        tuning.phase == TuningPhase.Unresponsive -> DisabledReason.Unresponsive
        connectionState == Connection.Disconnected -> DisabledReason.Disconnected
        connectionState == Connection.PermissionPending -> DisabledReason.PermissionPending
        !connected -> DisabledReason.Connecting
        tuning.phase == TuningPhase.Unconfirmed -> DisabledReason.Unconfirmed
        tuning.confirmed == null -> DisabledReason.Connecting
        empty && tuning.phase !in listOf(TuningPhase.Seeking, TuningPhase.SeekIncomplete) -> DisabledReason.EmptySelection
        else -> null
    }
    val enabled = buildSet {
        add(Control.EnterParking)
        add(Control.ActivateSelection)
        if (!connected) add(Control.RetryPermission) else {
            addAll(listOf(Control.Start, Control.Stop, Control.SelectStation, Control.CheckConnection))
            if (!empty) add(Control.MoveInSelection)
            if (tuning.pause?.kind != PauseKind.Step) add(Control.Step)
            if (tuning.pause?.kind != PauseKind.Seek && tuning.phase != TuningPhase.Seeking) add(Control.Seek)
        }
    }
    return RadioUiState(ScreenMode.Efir, driving, ReceiverView(moduleOption.id, listOf(moduleOption), connectionState, features),
        tuning.copy(wheelSeekHintSeconds = seekHint), PlaybackView(if (playing) PlaybackStatus.Playing else PlaybackStatus.Stopped,
            if (noFocus) FocusEffect.AutostartDenied else FocusEffect.Normal), stations,
        sampleSettings.copy(parkShowLabel = parkShowLabel, parkLargeIcon = parkLargeIcon), sampleDiagnostics, enabled, reason, null)
}

internal fun parking(
    stations: StationsView = stationsView(), confirmed: StationView? = null, settings: SettingsView = sampleSettings,
    diagnostics: DiagnosticsView = sampleDiagnostics, profiles: List<ReceiverOption> = listOf(moduleOption),
    selected: ReceiverId? = moduleOption.id, connected: Boolean = false
) = sampleRadio(stations, tuning(confirmed = confirmed), connected).let {
    it.copy(mode = ScreenMode.Parking, settings = settings, diagnostics = diagnostics,
        receiver = it.receiver.copy(selected = selected, availableProfiles = profiles), enabled = Control.entries.toSet())
}
