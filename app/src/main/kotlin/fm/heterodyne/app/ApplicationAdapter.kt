package fm.heterodyne.app

import android.content.Context
import android.os.SystemClock
import fm.heterodyne.app.module.Band
import fm.heterodyne.app.module.ConnectionState
import fm.heterodyne.app.module.ModuleController
import fm.heterodyne.app.module.TransitionState
import fm.heterodyne.app.module.TuneResult
import fm.heterodyne.app.module.isSelectableFrequency
import fm.heterodyne.app.receiver.Receiver
import fm.heterodyne.app.receiver.ReceiverKind
import fm.heterodyne.ui.contract.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** The main looper admits UI intents and publishes snapshots. IO stays with its existing owner. */
class ApplicationAdapter internal constructor(
    context: Context,
    private val module: StationsModule,
    private val session: StationsSession,
    private val diagnostics: StationsDiagnostics,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : RadioUi {
    private val settings = RadioSettings(context)
    private var foreground = false
    private var parked = false
    @Volatile private var epoch = 0L
    private var generation = module.generation
    private var moving = module.driving
    private var cursor: Int? = null
    private var playbackRequest = 0L
    private var issue: ActionIssue? = null
    private val requests = mutableMapOf<Control, Long>()
    private var detail: DeveloperDetail? = null
    private var diagnosticResult: DiagnosticResult? = null
    @Volatile private var toolsGeneration = 0L
    private var reading = false
    private var readAt: Long? = null
    private var screen = session.screen
    private var stations = screen.view()
    private var pause = module.transition.pause
    private var deadline = module.transition.pauseView(timeSource)
    private val profiles = module.receiverKinds.map { ReceiverOption(ReceiverId(it.value), module.receiverName(it)) }.readOnly()
    private val snapshots = MutableStateFlow(snapshot())
    override val state = snapshots.asStateFlow()

    init {
        module.changed = ::publish
        session.changed = ::publish
        session.captureAccess = { parkedOnly ->
            val admitted = foreground && (!parkedOnly || parked)
            val current = capture()
            val valid = { admitted && current() }
            valid
        }
        diagnostics.changed = ::publish
    }

    fun foreground(value: Boolean) {
        if (value == foreground) return
        foreground = value
        if (!value) setParking(false)
        invalidate()
        publish()
    }
    private fun capture(): () -> Boolean {
        val access = epoch
        val receiver = module.currentReceiver()
        return { epoch == access && receiver() }
    }
    private fun invalidate(accessChanged: Boolean = true) {
        if (accessChanged) epoch++
        issue = null
        detail = null
        diagnosticResult = null
        session.clearPublishedEditIssue()
    }
    internal fun closeTools() {
        toolsGeneration++
        detail = null
        diagnosticResult = null
        publish()
    }
    private fun setParking(value: Boolean) {
        if (parked == value) return
        parked = value
        invalidate()
        session.allowCatalogUpdate(value)
    }
    internal fun publish() {
        if (generation != module.generation || moving != module.driving) {
            invalidate(accessChanged = moving != module.driving)
            generation = module.generation
            moving = module.driving
            cursor = null
            if (moving) setParking(false)
        }
        if (screen !== session.screen) {
            val next = session.screen
            val id = next.state?.selectedRegion
            if (id != stations.activeRegionId || id?.let { next.state?.regionSelections(it)?.activeName } != stations.activeSelectionName) {
                issue = null
                cursor = null
            }
            val unchangedLists = screen.state?.selectedRegion == id && screen.catalogs === next.catalogs &&
                screen.state?.regions === next.state?.regions && screen.state?.regionNames == next.state?.regionNames
            stations = if (unchangedLists) stations.copy(dataIssue = next.typedDataIssue, editIssue = next.editIssue,
                catalogUpdate = next.update) else next.view()
            screen = next
        }
        if (pause != module.transition.pause) {
            pause = module.transition.pause
            deadline = module.transition.pauseView(timeSource)
        }
        if (module.transition.target == null && cursor == module.currentStation?.frequencyKhz) cursor = null
        snapshots.value = snapshot()
    }

    private val connected get() = module.moduleState == ConnectionState.Observing
    private val isModule get() = module.selectedReceiver == ReceiverKind.Module
    private val band get() = module.transition.confirmed?.band
    private val developer get() = diagnostics.developerMode.enabled

    private fun snapshot(): RadioUiState {
        val tune = module.transition
        val confirmed = module.currentStation?.let { stations.station(it.frequencyKhz) }
        val saved = screen.state?.lastStation?.takeUnless { band == Band.AM }?.let { stations.station(it.frequencyKhz) }
        val reason = disabledReason(module.moduleState, tune.state, band, module.activeEntries.isEmpty())
        return RadioUiState(if (parked) ScreenMode.Parking else ScreenMode.Efir, moving,
            ReceiverView(module.selectedReceiver?.let { ReceiverId(it.value) }, profiles, enumValueOf(module.moduleState.name),
                if (module.capabilities.seek) setOf(Feature.Seek) else emptySet()),
            TuningView(confirmed, tune.target?.frequencyKhz, saved, enumValueOf(tune.state.name), deadline, module.seekHintSeconds),
            PlaybackView(enumValueOf(module.audioState.name), if (module.noFocus) FocusEffect.AutostartDenied else module.focusEffect),
            stations, settings.snapshot(isModule),
            DiagnosticsView(BuildConfig.VERSION_NAME, developer, if (parked) diagnostics.export else ExportState.Idle,
                detail?.copy(commands = commands(), commandResult = diagnosticResult).takeIf { parked && developer }),
            java.util.Collections.unmodifiableSet(Control.entries.filterTo(mutableSetOf()) { refusal(it) == null }), reason, issue)
    }

    private fun commands(): Set<DiagnosticAction> = buildSet {
        if (!parked || !developer || !isModule || !connected) return@buildSet
        add(DiagnosticAction.CheckLink)
        when (band) {
            Band.AM -> add(DiagnosticAction.ReturnToFm)
            Band.FM -> {
                add(DiagnosticAction.ReselectCurrent)
                add(DiagnosticAction.StepFrequencyUp)
                add(DiagnosticAction.StepFrequencyDown)
                if (module.capabilities.seek) {
                    add(DiagnosticAction.SeekFrequencyUp)
                    add(DiagnosticAction.SeekFrequencyDown)
                }
            }
            else -> Unit
        }
    }

    private fun refusal(control: Control): RejectReason? {
        if (!foreground) return RejectReason.NotForeground
        return when (control) {
            Control.EnterParking -> RejectReason.WrongMode.takeIf { moving || parked }
            Control.ExitParking -> RejectReason.WrongMode.takeUnless { parked }
            Control.ActivateSelection -> RejectReason.StaleContext.takeUnless { stations.loaded }
            Control.SelectRegion, Control.DeleteObsoleteRegion, Control.Edit, Control.UpdateCatalog,
            Control.ChangeSetting, Control.SetDeveloper, Control.ExportJournal, Control.RefreshDiagnostics, Control.RunDiagnostic -> when {
                !parked || moving -> RejectReason.WrongMode
                control in listOf(Control.RefreshDiagnostics, Control.RunDiagnostic) && !developer -> RejectReason.WrongMode
                control == Control.RunDiagnostic && !isModule -> RejectReason.Unsupported
                control == Control.RunDiagnostic && !connected -> RejectReason.NotConnected
                control in listOf(Control.ExportJournal, Control.RunDiagnostic) && diagnostics.exportBusy -> RejectReason.Busy
                control == Control.RefreshDiagnostics && reading -> RejectReason.Busy
                control == Control.UpdateCatalog && session.catalogBusy -> RejectReason.Busy
                else -> null
            }
            else -> when {
                parked -> RejectReason.WrongMode
                control == Control.RetryPermission -> RejectReason.IncompatibleState.takeUnless { module.moduleState == ConnectionState.Disconnected }
                control == Control.Stop -> null // Also cancels a pending R25 launch.
                !connected -> RejectReason.NotConnected
                control == Control.Seek && moving -> RejectReason.WrongMode
                control == Control.Seek && !module.capabilities.seek -> RejectReason.Unsupported
                control == Control.CheckConnection && module.transition.state != TransitionState.Unconfirmed -> RejectReason.IncompatibleState
                control in listOf(Control.TuneFrequency, Control.SelectStation, Control.MoveInSelection) &&
                    module.transition.state == TransitionState.Seeking -> RejectReason.Busy
                control == Control.Seek && module.transition.state == TransitionState.Seeking -> RejectReason.Busy
                control == Control.Step && pause?.kind == fm.heterodyne.app.receiver.PauseKind.Step -> RejectReason.RateLimited
                control == Control.Seek && pause?.kind == fm.heterodyne.app.receiver.PauseKind.Seek -> RejectReason.RateLimited
                control == Control.MoveInSelection && module.activeEntries.isEmpty() -> RejectReason.IncompatibleState
                control in listOf(Control.Step, Control.Seek) && band != Band.FM -> RejectReason.IncompatibleState
                else -> null
            }
        }
    }

    override suspend fun dispatch(action: RadioAction): Admission =
        withContext(Dispatchers.Main.immediate) { submit(action) }

    internal fun submit(action: RadioAction): Admission {
        val control = action.control
        if (issue?.control == control) {
            issue = null
            publish()
        }
        refusal(control)?.let { return Admission.Rejected(it) }
        validate(action)?.let { return Admission.Rejected(it) }
        val access = epoch
        val valid = capture()
        if (!valid()) return Admission.Rejected(RejectReason.StaleContext)
        val request = (requests[control] ?: 0) + 1
        requests[control] = request
        fun completed(reason: RejectReason?) {
            if (epoch == access && requests[control] == request && (valid() || reason == RejectReason.TransportError)) {
                if (reason != null) issue = ActionIssue(control, reason)
                publish()
            }
        }
        when (action) {
            RadioAction.EnterParking -> setParking(true)
            RadioAction.ExitParking -> setParking(false)
            is RadioAction.TuneFrequency, is RadioAction.SelectStation, is RadioAction.MoveInSelection -> {
                val khz = when (action) {
                    is RadioAction.TuneFrequency -> action.frequencyKhz
                    is RadioAction.SelectStation -> checkNotNull(stations.resolve(action))
                    is RadioAction.MoveInSelection -> nextFrequency(action.direction)
                    else -> error("Expected a transition")
                }
                val play = ++playbackRequest
                cursor = khz
                module.cancelAutostart()
                val entryValid = session.stationAccess(action, khz)
                module.usb.tune(khz, { valid() && entryValid() }) { result ->
                    if (epoch != access || !entryValid()) return@tune
                    if (result == null && play == playbackRequest) module.startAudio(reclaim = true)
                    completed(result)
                }
            }
            is RadioAction.Step, is RadioAction.Seek, RadioAction.CheckConnection -> {
                if (action != RadioAction.CheckConnection) { cursor = null; module.cancelAutostart() }
                module.usb.command(valid, { completed(it.reason()) }) { receiver ->
                    when (action) {
                        is RadioAction.Step -> receiver.step(enumValueOf(action.direction.name))
                        is RadioAction.Seek -> receiver.seek(enumValueOf(action.direction.name))
                        else -> if (receiver.transitionState == TransitionState.Unconfirmed) receiver.checkConnection()
                            else TuneResult.IncompatibleState
                    }
                }
            }
            RadioAction.Start -> { playbackRequest++; module.startAudio() }
            RadioAction.Stop -> { playbackRequest++; module.userStop() }
            RadioAction.RetryPermission -> module.usb.retry(valid)
            is RadioAction.SelectRegion -> session.selectRegion(action.id)
            is RadioAction.DeleteObsoleteRegion -> session.deleteObsoleteRegion(action.id)
            is RadioAction.ActivateSelection -> { cursor = null; session.activateSelection(action.name, action.regionId) }
            is RadioAction.Edit -> session.edit(action.change)
            RadioAction.UpdateCatalog -> session.updateCatalog()
            is RadioAction.ChangeSetting -> settings.apply(action.change) { id ->
                module.cancelAutostart()
                module.usb.selectReceiver(ReceiverKind(id.value), valid) { epoch == access }
            }
            is RadioAction.SetDeveloper -> {
                if (developer != action.enabled) invalidate()
                diagnostics.developerMode.setEnabled(action.enabled, parked)
            }
            RadioAction.ExportJournal -> diagnostics.exportJournal(valid)
            RadioAction.RefreshDiagnostics -> {
                reading = true
                readAt = SystemClock.elapsedRealtime()
                val tools = toolsGeneration
                diagnostics.readDetail({ valid() && tools == toolsGeneration }) {
                    reading = false
                    if (valid() && tools == toolsGeneration) detail = it
                    publish()
                }
            }
            is RadioAction.RunDiagnostic -> {
                module.cancelAutostart()
                val tools = toolsGeneration
                module.usb.command({ valid() && tools == toolsGeneration }, { result ->
                    if (tools != toolsGeneration || epoch != access || requests[control] != request) return@command
                    diagnosticResult = DiagnosticResult(action.command, when (result) {
                        TuneResult.BandNotFM -> DiagnosticOutcome.IncompatibleState
                        else -> enumValueOf(result.name)
                    })
                    completed(result.reason())
                }) { it.diagnostic(action.command) }
            }
        }
        publish()
        return Admission.Accepted
    }

    private fun validate(action: RadioAction): RejectReason? = when (action) {
        is RadioAction.TuneFrequency -> RejectReason.InvalidInput.takeUnless { isSelectableFrequency(Band.FM, action.frequencyKhz) }
        is RadioAction.SelectStation -> RejectReason.StaleContext.takeIf { stations.resolve(action) == null }
        is RadioAction.ActivateSelection -> RejectReason.StaleContext.takeIf { action.regionId != stations.activeRegionId || stations.selections.none { it.name == action.name } }
        is RadioAction.SelectRegion -> RejectReason.StaleContext.takeIf { stations.regions.none { it.id == action.id } }
        is RadioAction.DeleteObsoleteRegion -> RejectReason.StaleContext.takeIf { stations.regions.none { it.id == action.id && it.obsolete } }
        is RadioAction.ChangeSetting -> when (val change = action.change) {
            is SettingChange.Receiver -> RejectReason.Unsupported.takeIf { profiles.none { it.id == change.id } }
            is SettingChange.SeekLimit -> if (!isModule) RejectReason.Unsupported else RejectReason.InvalidInput.takeUnless { change.seconds in 10..60 step 5 }
            is SettingChange.TransitionSignal -> RejectReason.Unsupported.takeUnless { isModule }
            else -> null
        }
        is RadioAction.Edit -> {
            val ref = action.change.selectionRef
            when {
                ref == null -> null
                ref.regionId != stations.activeRegionId -> RejectReason.StaleContext
                action.change !is SelectionEdit.Create && stations.selections.none { it.name == ref.name } -> RejectReason.StaleContext
                else -> null
            }
        }
        is RadioAction.RunDiagnostic -> RejectReason.IncompatibleState.takeUnless { action.command in commands() }
        RadioAction.RefreshDiagnostics -> RejectReason.RateLimited.takeIf { readAt?.let { SystemClock.elapsedRealtime() - it < 1000 } == true }
        else -> null
    }

    private fun nextFrequency(direction: Direction): Int {
        val entries = module.activeEntries
        val index = entries.indexOfFirst { it.frequencyKhz == (cursor ?: module.currentStation?.frequencyKhz) }
        val next = if (index < 0) { if (direction == Direction.Up) 0 else entries.lastIndex }
            else Math.floorMod(index + if (direction == Direction.Up) 1 else -1, entries.size)
        return entries[next].frequencyKhz
    }
}

internal fun TuneResult.reason(): RejectReason? = when (this) {
    TuneResult.Sent -> null
    TuneResult.InvalidFrequency -> RejectReason.InvalidInput
    TuneResult.NotConnected -> RejectReason.NotConnected
    TuneResult.BandNotFM, TuneResult.IncompatibleState -> RejectReason.IncompatibleState
    TuneResult.RateLimited -> RejectReason.RateLimited
    TuneResult.TransportError -> RejectReason.TransportError
}

private fun Receiver.diagnostic(action: DiagnosticAction): TuneResult = when (action) {
    DiagnosticAction.CheckLink -> checkConnection()
    DiagnosticAction.ReturnToFm -> switchBand()
    DiagnosticAction.StepFrequencyUp -> step(fm.heterodyne.app.module.Direction.Up)
    DiagnosticAction.StepFrequencyDown -> step(fm.heterodyne.app.module.Direction.Down)
    DiagnosticAction.SeekFrequencyUp -> seek(fm.heterodyne.app.module.Direction.Up)
    DiagnosticAction.SeekFrequencyDown -> seek(fm.heterodyne.app.module.Direction.Down)
    DiagnosticAction.ReselectCurrent -> (this as? ModuleController)?.reselectCurrent() ?: TuneResult.BandNotFM
}
