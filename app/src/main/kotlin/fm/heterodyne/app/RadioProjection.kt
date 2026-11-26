package fm.heterodyne.app

import android.content.Context
import fm.heterodyne.app.host.*
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.module.TransitionSnapshot
import fm.heterodyne.app.stations.StationEntry
import fm.heterodyne.ui.contract.*
import fm.heterodyne.ui.contract.Palette
import fm.heterodyne.ui.contract.ResolvedTheme
import fm.heterodyne.ui.contract.ScaleMarker
import fm.heterodyne.ui.contract.ThemeChoice
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class RadioSettings(private val context: Context) {
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val appearance = AppearancePreferences(prefs("appearance"))
    private val park = ParkButtonPreferences(prefs("park-button"))
    private val marker = ScaleMarkerPreferences(prefs("scale-marker"))
    private val start = AutostartPreferences(prefs("autostart"))
    private val seek = SeekPreferences(prefs("seek"))
    private val signal = TransitionSignalPreferences(prefs("transition-signal"))

    fun snapshot(module: Boolean): SettingsView {
        val night = headUnitNight(context.resources.configuration.uiMode)
        return SettingsView(ThemeChoice.valueOf(appearance.theme.name),
            ResolvedTheme.valueOf(resolveTheme(appearance.theme, night).name), Palette.valueOf(appearance.palette.name),
            park.showLabel, park.largeIcon, ScaleMarker.valueOf(marker.marker.name), start.enabled,
            seek.seconds.takeIf { module }, SignalChoice.valueOf(signal.method.name).takeIf { module })
    }
    fun apply(change: SettingChange, selectReceiver: (ReceiverId) -> Unit) {
        when (change) {
            is SettingChange.Theme -> appearance.selectTheme(enumValueOf(change.value.name))
            is SettingChange.PaletteChoice -> appearance.selectPalette(enumValueOf(change.value.name))
            is SettingChange.ParkLabel -> park.selectShowLabel(change.show)
            is SettingChange.ParkIcon -> park.selectLargeIcon(change.large)
            is SettingChange.Marker -> marker.selectMarker(enumValueOf(change.value.name))
            is SettingChange.Autostart -> start.selectEnabled(change.enabled)
            is SettingChange.SeekLimit -> seek.selectSeconds(change.seconds)
            is SettingChange.TransitionSignal -> signal.selectMethod(enumValueOf(change.value.name))
            is SettingChange.Receiver -> selectReceiver(change.id)
        }
    }
}

internal fun <T> List<T>.readOnly(): List<T> = java.util.Collections.unmodifiableList(this)

internal val StationsScreenState.typedDataIssue get() = dataIssue ?: DataIssue.Recovered.takeIf { recovered }

internal fun StationEntry.view() = StationView(frequencyKhz, name)

internal fun StationsScreenState.view(): StationsView {
    val id = state?.selectedRegion
    val selections = id?.let { state?.regionSelections(it) }
    val catalog = catalogs.firstOrNull { it.region.id == id }
    return StationsView(state != null,
        regions.map { region -> RegionView(region.id, region.name, catalogs.none { it.region.id == region.id }) }.readOnly(),
        id, selections?.selections.orEmpty().map { SelectionView(it.name, it.entries.map(StationEntry::view).readOnly()) }.readOnly(),
        selections?.activeName, catalog?.stations.orEmpty().map { CatalogStationView(it.id, it.frequencyKhz, it.name) }.readOnly(),
        catalog?.source?.let { CatalogSourceView(it.text, it.url) }, typedDataIssue,
        editIssue, update)
}

internal fun StationsView.station(khz: Int): StationView {
    val name = selections.firstOrNull { it.name == activeSelectionName }?.entries?.firstOrNull { it.frequencyKhz == khz }
        ?.name?.takeIf { it.isNotBlank() } ?: catalog.firstOrNull { it.frequencyKhz == khz }?.name
    return StationView(khz, name)
}

internal fun disabledReason(connection: ConnectionState, phase: TransitionState, band: Band?, emptySelection: Boolean): DisabledReason? = when {
    phase == TransitionState.Unresponsive -> DisabledReason.Unresponsive
    connection == ConnectionState.Disconnected -> DisabledReason.Disconnected
    connection == ConnectionState.PermissionPending -> DisabledReason.PermissionPending
    connection != ConnectionState.Observing -> DisabledReason.Connecting
    phase == TransitionState.Unconfirmed -> DisabledReason.Unconfirmed
    band == null -> DisabledReason.Connecting
    emptySelection && phase !in listOf(TransitionState.Seeking, TransitionState.SeekIncomplete) -> DisabledReason.EmptySelection
    else -> null
}

internal fun TransitionSnapshot.pauseView(): CommandPauseView? = pause?.let {
    val remaining = it.publishedAtMillis + it.remainingMillis - android.os.SystemClock.elapsedRealtime()
    CommandPauseView(PauseKind.valueOf(it.kind.name), Direction.valueOf(it.direction.name), it.durationMillis,
        TimeSource.Monotonic.markNow() + remaining.milliseconds)
}

internal val RadioAction.control: Control get() = when (this) {
    is RadioAction.TuneFrequency -> Control.TuneFrequency
    is RadioAction.SelectStation -> Control.SelectStation
    is RadioAction.MoveInSelection -> Control.MoveInSelection
    is RadioAction.Step -> Control.Step
    is RadioAction.Seek -> Control.Seek
    RadioAction.Start -> Control.Start
    RadioAction.Stop -> Control.Stop
    RadioAction.CheckConnection -> Control.CheckConnection
    RadioAction.RetryPermission -> Control.RetryPermission
    RadioAction.EnterParking -> Control.EnterParking
    RadioAction.ExitParking -> Control.ExitParking
    is RadioAction.ActivateSelection -> Control.ActivateSelection
    is RadioAction.SelectRegion -> Control.SelectRegion
    is RadioAction.DeleteObsoleteRegion -> Control.DeleteObsoleteRegion
    is RadioAction.Edit -> Control.Edit
    RadioAction.UpdateCatalog -> Control.UpdateCatalog
    is RadioAction.ChangeSetting -> Control.ChangeSetting
    is RadioAction.SetDeveloper -> Control.SetDeveloper
    RadioAction.RefreshDiagnostics -> Control.RefreshDiagnostics
    RadioAction.ExportJournal -> Control.ExportJournal
    is RadioAction.RunDiagnostic -> Control.RunDiagnostic
}

internal fun StationsView.resolve(action: RadioAction.SelectStation): Int? {
    if (action.regionId != activeRegionId) return null
    return when (val ref = action.ref) {
        is StationRef.Catalog -> catalog.firstOrNull { it.id == ref.stationId }?.frequencyKhz
        is StationRef.Selection -> selections.firstOrNull { it.name == ref.selectionName }?.entries
            ?.firstOrNull { it.frequencyKhz == ref.frequencyKhz }?.frequencyKhz
    }
}

internal fun StationsSession.edit(change: SelectionEdit) {
    when (change) {
        is SelectionEdit.Create -> createSelection(change.selection.regionId, change.selection.name)
        is SelectionEdit.Rename -> renameSelection(change.selection.regionId, change.selection.name, change.newName)
        is SelectionEdit.Delete -> deleteSelection(change.selection.regionId, change.selection.name)
        is SelectionEdit.Add -> addStation(change.selection.regionId, change.selection.name,
            StationEntry(change.station.frequencyKhz, change.station.name, change.catalogId))
        is SelectionEdit.EditName -> editStation(change.selection.regionId, change.selection.name,
            StationEntry(change.frequencyKhz), change.newName)
        is SelectionEdit.Remove -> removeStation(change.selection.regionId, change.selection.name, StationEntry(change.frequencyKhz))
        is SelectionEdit.Move -> moveStation(change.selection.regionId, change.selection.name, StationEntry(change.frequencyKhz), change.toIndex)
        SelectionEdit.ClearIssue -> clearNameError()
    }
}


internal val SelectionEdit.selectionRef: SelectionRef? get() = when (this) {
    is SelectionEdit.Create -> selection
    is SelectionEdit.Rename -> selection
    is SelectionEdit.Delete -> selection
    is SelectionEdit.Add -> selection
    is SelectionEdit.EditName -> selection
    is SelectionEdit.Remove -> selection
    is SelectionEdit.Move -> selection
    SelectionEdit.ClearIssue -> null
}
