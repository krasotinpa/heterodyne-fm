package fm.heterodyne.ui.contract

sealed interface RadioAction {
    data class TuneFrequency(val frequencyKhz: Int) : RadioAction
    data class SelectStation(val regionId: String, val ref: StationRef) : RadioAction
    data class MoveInSelection(val direction: Direction) : RadioAction
    data class Step(val direction: Direction) : RadioAction
    data class Seek(val direction: Direction) : RadioAction
    data object Start : RadioAction
    data object Stop : RadioAction
    data object CheckConnection : RadioAction
    data object RetryPermission : RadioAction
    data object EnterParking : RadioAction
    data object ExitParking : RadioAction
    data class ActivateSelection(val regionId: String, val name: String) : RadioAction
    data class SelectRegion(val id: String) : RadioAction
    data class DeleteObsoleteRegion(val id: String) : RadioAction
    data class Edit(val change: SelectionEdit) : RadioAction
    data object UpdateCatalog : RadioAction
    data class ChangeSetting(val change: SettingChange) : RadioAction
    data class SetDeveloper(val enabled: Boolean) : RadioAction
    data object RefreshDiagnostics : RadioAction
    data object ExportJournal : RadioAction
    data class RunDiagnostic(val command: DiagnosticAction) : RadioAction
}

sealed interface StationRef {
    data class Catalog(val stationId: String) : StationRef
    data class Selection(val selectionName: String, val frequencyKhz: Int) : StationRef
}
data class SelectionRef(val regionId: String, val name: String)
sealed interface SelectionEdit {
    data class Create(val selection: SelectionRef) : SelectionEdit
    data class Rename(val selection: SelectionRef, val newName: String) : SelectionEdit
    data class Delete(val selection: SelectionRef) : SelectionEdit
    data class Add(val selection: SelectionRef, val station: StationView,
                   val catalogId: String? = null) : SelectionEdit
    data class EditName(val selection: SelectionRef, val frequencyKhz: Int, val newName: String) : SelectionEdit
    data class Remove(val selection: SelectionRef, val frequencyKhz: Int) : SelectionEdit
    data class Move(val selection: SelectionRef, val frequencyKhz: Int, val toIndex: Int) : SelectionEdit
    data object ClearIssue : SelectionEdit
}

sealed interface SettingChange {
    data class Theme(val value: ThemeChoice) : SettingChange
    data class PaletteChoice(val value: Palette) : SettingChange
    data class ParkLabel(val show: Boolean) : SettingChange
    data class ParkIcon(val large: Boolean) : SettingChange
    data class Marker(val value: ScaleMarker) : SettingChange
    data class Autostart(val enabled: Boolean) : SettingChange
    data class SeekLimit(val seconds: Int) : SettingChange
    data class TransitionSignal(val value: SignalChoice) : SettingChange
    data class Receiver(val id: ReceiverId) : SettingChange
}
