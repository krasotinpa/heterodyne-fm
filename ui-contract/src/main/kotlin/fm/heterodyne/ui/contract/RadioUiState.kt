package fm.heterodyne.ui.contract

import kotlin.time.TimeMark

data class RadioUiState(
    val mode: ScreenMode,
    val moving: Boolean,
    val receiver: ReceiverView,
    val tuning: TuningView,
    val playback: PlaybackView,
    val stations: StationsView,
    val settings: SettingsView,
    val diagnostics: DiagnosticsView,
    val enabled: Set<Control>,
    val disabledReason: DisabledReason?,
    val actionIssue: ActionIssue?
)

data class ReceiverView(
    val selected: ReceiverId?,
    val availableProfiles: List<ReceiverOption>,
    val connection: Connection,
    val features: Set<Feature>
)
enum class Connection { Disconnected, DeviceFound, PermissionPending, Open, Observing }
enum class Feature { Seek }

/** Frequencies are integer kHz. An accepted target or a saved station does not confirm tuning. */
data class TuningView(
    val confirmed: StationView?,
    val targetKhz: Int?,
    val saved: StationView?,
    val phase: TuningPhase,
    val pause: CommandPauseView?,
    val wheelSeekHintSeconds: Int?
)
enum class TuningPhase {
    Idle, Waiting, Unconfirmed, Unresponsive, Disconnected, CommandPause, Seeking, SeekIncomplete
}
/** Deadline uses a monotonic clock. A new subscriber can calculate the remaining time. */
data class CommandPauseView(
    val kind: PauseKind,
    val direction: Direction,
    val totalMillis: Long,
    val deadline: TimeMark,
)
data class StationView(val frequencyKhz: Int, val name: String?)
data class PlaybackView(val status: PlaybackStatus, val focus: FocusEffect)
enum class PlaybackStatus { Stopped, Starting, Playing, Failed }
enum class FocusEffect { None, Normal, Ducking, Muted, PermanentLossContinuing, AutostartDenied }

data class StationsView(
    val loaded: Boolean,
    val regions: List<RegionView>,
    val activeRegionId: String?,
    val selections: List<SelectionView>,
    val activeSelectionName: String?,
    val catalog: List<CatalogStationView>,
    val catalogSource: CatalogSourceView?,
    val dataIssue: DataIssue?,
    val editIssue: EditIssue?,
    val catalogUpdate: CatalogUpdate
)
data class RegionView(val id: String, val name: String, val obsolete: Boolean)
data class SelectionView(val name: String, val entries: List<StationView>)
data class CatalogStationView(val id: String, val frequencyKhz: Int, val name: String)
data class CatalogSourceView(val name: String, val url: String)
data class SettingsView(
    val theme: ThemeChoice, val resolvedTheme: ResolvedTheme, val palette: Palette,
    val parkShowLabel: Boolean, val parkLargeIcon: Boolean, val scaleMarker: ScaleMarker,
    val autostart: Boolean, val seekLimitSeconds: Int?, val transitionSignal: SignalChoice?
)
data class DiagnosticsView(
    val buildVersion: String, val developerEnabled: Boolean,
    val export: ExportState, val detail: DeveloperDetail?
)
/** The publisher exposes these details only while the diagnostics guard allows them. */
data class DeveloperDetail(
    val commands: Set<DiagnosticAction>, val frames: List<String>, val journalTail: String?,
    val audio: AudioMetrics, val environmentLines: List<String>, val rawByte7: Int?,
    val commandResult: DiagnosticResult?
)

enum class ScreenMode { Efir, Parking }
enum class Direction { Up, Down }
enum class PauseKind { Step, Seek }
enum class DisabledReason { Unresponsive, Disconnected, PermissionPending, Connecting, Unconfirmed, EmptySelection }
enum class Control {
    TuneFrequency, SelectStation, MoveInSelection, Step, Seek, Start, Stop, CheckConnection,
    RetryPermission, EnterParking, ExitParking, ActivateSelection, SelectRegion,
    DeleteObsoleteRegion, Edit, UpdateCatalog, ChangeSetting, SetDeveloper,
    RefreshDiagnostics, ExportJournal, RunDiagnostic
}

@JvmInline value class ReceiverId(val value: String)
data class ReceiverOption(val id: ReceiverId, val displayName: String)

enum class ThemeChoice { Auto, Dark, Light }
enum class ResolvedTheme { Dark, Light }
enum class Palette { Coral, Amber, Plum, Lime }
enum class ScaleMarker { Pin, Flag, Needle, Drop, Stripe }
enum class SignalChoice { Off, Tone, KeyClick, Silence }

enum class DataIssue { LoadFailed, Recovered, SaveFailed }
enum class EditIssue { InvalidName, DuplicateName, InvalidFrequency, DuplicateFrequency, LastSelection, NotFound }
sealed interface CatalogUpdate {
    data object Idle : CatalogUpdate
    data object InProgress : CatalogUpdate
    data object NoUpdate : CatalogUpdate
    data class Updated(val revision: String) : CatalogUpdate
    data object Failed : CatalogUpdate
}
sealed interface ExportState {
    data object Idle : ExportState
    data object InProgress : ExportState
    data class Exported(val path: String, val sizeBytes: Long) : ExportState
    data object Failed : ExportState
}
enum class DiagnosticAction {
    CheckLink, StepFrequencyUp, StepFrequencyDown, ReturnToFm, ReselectCurrent,
    SeekFrequencyUp, SeekFrequencyDown
}
enum class DiagnosticOutcome { Sent, InvalidFrequency, NotConnected, IncompatibleState, RateLimited, TransportError }
data class DiagnosticResult(val action: DiagnosticAction, val outcome: DiagnosticOutcome)
data class AudioMetrics(
    val bytes: Long?, val badPackets: Long?, val realignments: Int?, val underruns: Int?,
    val maxReadGapMillis: Long?, val longPauses: Int?, val minBufferedFrames: Long?,
    val lostLogRecords: Long?, val logWriteFailures: Long?
)
