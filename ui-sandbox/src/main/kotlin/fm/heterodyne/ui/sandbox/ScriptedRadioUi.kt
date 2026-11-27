package fm.heterodyne.ui.sandbox

import fm.heterodyne.ui.contract.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** One in-memory receiver, driven only by gestures and the scenario clock on the UI thread. */
internal class ScriptedRadioUi(val scenario: Scenario) : RadioUi {
    private val clock = TestTimeSource()
    private val origin = clock.markNow()
    val elapsedMillis get() = origin.elapsedNow().inWholeMilliseconds
    private var operationStarted = clock.markNow()
    private var scenarioFocus = FocusEffect.Normal
    private var checkingLink = false
    private var seekLimitMillis = 0L
    private val snapshots = MutableStateFlow(initialSnapshot(scenario))
    override val state = snapshots.asStateFlow()
    private val current get() = snapshots.value

    init {
        publish(current)
        when (scenario) {
            Scenario.Offline -> detach()
            Scenario.Tune, Scenario.TuneTimeout -> tune(second.frequencyKhz)
            Scenario.Seek -> act(RadioAction.Seek(Direction.Up))
            Scenario.NoReply -> act(RadioAction.Seek(Direction.Down))
            Scenario.Focus -> act(RadioAction.Start)
            else -> Unit
        }
    }

    private fun publish(snapshot: RadioUiState) {
        val tuning = snapshot.tuning
        val connected = snapshot.receiver.connection == Connection.Observing
        val seeking = tuning.phase == TuningPhase.Seeking
        val enabled = buildSet {
            add(Control.ActivateSelection)
            if (snapshot.mode == ScreenMode.Parking) {
                add(Control.ExitParking)
                add(Control.ChangeSetting)
                add(Control.SetDeveloper)
                if (snapshot.diagnostics.developerEnabled) add(Control.RefreshDiagnostics)
            } else {
                if (!snapshot.moving) add(Control.EnterParking)
                add(Control.Stop)
                if (connected) {
                    add(Control.Start)
                    if (!seeking && !checkingLink) {
                        add(Control.SelectStation)
                        add(Control.TuneFrequency)
                        if (!snapshot.moving && Feature.Seek in snapshot.receiver.features &&
                            tuning.targetKhz == null && tuning.confirmed != null) add(Control.Seek)
                    }
                    if (tuning.phase == TuningPhase.Unconfirmed && !checkingLink) add(Control.CheckConnection)
                }
            }
        }
        val reason = when {
            !connected -> DisabledReason.Disconnected
            tuning.phase == TuningPhase.Unresponsive -> DisabledReason.Unresponsive
            tuning.phase == TuningPhase.Unconfirmed -> DisabledReason.Unconfirmed
            tuning.confirmed == null -> DisabledReason.Connecting
            else -> null
        }
        snapshots.value = snapshot.copy(enabled = enabled, disabledReason = reason)
    }

    private fun tune(khz: Int) {
        operationStarted = clock.markNow()
        publish(current.copy(tuning = current.tuning.copy(targetKhz = khz, phase = TuningPhase.Idle, pause = null),
            playback = PlaybackView(PlaybackStatus.Playing, scenarioFocus)))
    }

    fun advanceBy(millis: Long) {
        require(millis >= 0)
        clock += millis.milliseconds
        val tuning = current.tuning
        val elapsed = operationStarted.elapsedNow().inWholeMilliseconds
        val target = tuning.targetKhz
        val next = when {
            checkingLink -> {
                if (elapsed < TIMEOUT_MILLIS) return
                checkingLink = false
                tuning.copy(phase = TuningPhase.Unresponsive, targetKhz = null)
            }
            tuning.phase == TuningPhase.Seeking && elapsed >= seekLimitMillis ->
                tuning.copy(phase = TuningPhase.SeekIncomplete, pause = null)
            target == null -> return
            scenario != Scenario.TuneTimeout && elapsed >= REPLY_MILLIS -> {
                val station = StationView(target, current.stations.catalog.find { it.frequencyKhz == target }?.name)
                tuning.copy(confirmed = station, saved = station, targetKhz = null, phase = TuningPhase.Idle)
            }
            elapsed >= TIMEOUT_MILLIS -> tuning.copy(phase = TuningPhase.Unconfirmed, targetKhz = null)
            elapsed >= WAIT_MILLIS -> tuning.copy(phase = TuningPhase.Waiting)
            else -> return
        }
        publish(current.copy(tuning = next))
    }

    fun detach() = disconnect(current.receiver.copy(connection = Connection.Disconnected))

    private fun disconnect(receiver: ReceiverView) {
        checkingLink = false
        publish(current.copy(receiver = receiver, tuning = TuningView(null, null, current.tuning.saved,
            TuningPhase.Disconnected, null, null), playback = PlaybackView(PlaybackStatus.Stopped, FocusEffect.None),
            diagnostics = current.diagnostics.copy(detail = null)))
    }

    fun focus(effect: FocusEffect) {
        scenarioFocus = effect
        if (current.playback.status == PlaybackStatus.Playing) publish(current.copy(playback = current.playback.copy(focus = effect)))
    }

    fun moving(value: Boolean) = publish(current.copy(moving = value, mode = ScreenMode.Efir,
        diagnostics = current.diagnostics.copy(detail = null)))

    fun theme(choice: ThemeChoice) = publish(current.copy(settings = current.settings.copy(theme = choice,
        resolvedTheme = if (choice == ThemeChoice.Light) ResolvedTheme.Light else ResolvedTheme.Dark)))

    override suspend fun dispatch(action: RadioAction): Admission = act(action)

    internal fun act(action: RadioAction): Admission {
        val control = when (action) {
            RadioAction.Start -> Control.Start
            RadioAction.Stop -> Control.Stop
            RadioAction.CheckConnection -> Control.CheckConnection
            RadioAction.EnterParking -> Control.EnterParking
            RadioAction.ExitParking -> Control.ExitParking
            is RadioAction.Seek -> Control.Seek
            is RadioAction.TuneFrequency -> Control.TuneFrequency
            is RadioAction.SelectStation -> Control.SelectStation
            is RadioAction.ActivateSelection -> Control.ActivateSelection
            is RadioAction.ChangeSetting -> Control.ChangeSetting
            is RadioAction.SetDeveloper -> Control.SetDeveloper
            RadioAction.RefreshDiagnostics -> Control.RefreshDiagnostics
            else -> return Admission.Rejected(RejectReason.Unsupported)
        }
        if (control !in current.enabled) {
            val reason = when {
                checkingLink && current.mode == ScreenMode.Efir &&
                    (control == Control.TuneFrequency || control == Control.SelectStation) -> RejectReason.Busy
                current.tuning.phase == TuningPhase.Seeking -> RejectReason.Busy
                current.receiver.connection == Connection.Disconnected && current.mode == ScreenMode.Efir -> RejectReason.NotConnected
                control == Control.Seek && Feature.Seek !in current.receiver.features -> RejectReason.Unsupported
                else -> RejectReason.WrongMode
            }
            return Admission.Rejected(reason)
        }
        when (action) {
            RadioAction.Start -> if (current.playback.status != PlaybackStatus.Playing)
                publish(current.copy(playback = PlaybackView(PlaybackStatus.Playing, scenarioFocus)))
            RadioAction.Stop -> publish(current.copy(playback = PlaybackView(PlaybackStatus.Stopped, FocusEffect.None)))
            RadioAction.CheckConnection -> {
                checkingLink = true
                operationStarted = clock.markNow()
                publish(current)
            }
            RadioAction.EnterParking -> publish(current.copy(mode = ScreenMode.Parking))
            RadioAction.ExitParking -> publish(current.copy(mode = ScreenMode.Efir, diagnostics = current.diagnostics.copy(detail = null)))
            is RadioAction.Seek -> {
                operationStarted = clock.markNow()
                seekLimitMillis = requireNotNull(current.settings.seekLimitSeconds) * 1000L
                val pause = CommandPauseView(PauseKind.Seek, action.direction, 5000, operationStarted + 5000.milliseconds)
                publish(current.copy(tuning = current.tuning.copy(phase = TuningPhase.Seeking, pause = pause)))
            }
            is RadioAction.TuneFrequency -> {
                if (action.frequencyKhz !in 87500..108000 || action.frequencyKhz % 100 != 0)
                    return Admission.Rejected(RejectReason.InvalidInput)
                tune(action.frequencyKhz)
            }
            is RadioAction.SelectStation -> {
                val stations = current.stations
                if (action.regionId != stations.activeRegionId) return Admission.Rejected(RejectReason.StaleContext)
                val khz = when (val ref = action.ref) {
                    is StationRef.Catalog -> stations.catalog.find { it.id == ref.stationId }?.frequencyKhz
                    is StationRef.Selection -> stations.selections.find { it.name == ref.selectionName }?.entries
                        ?.find { it.frequencyKhz == ref.frequencyKhz }?.frequencyKhz
                } ?: return Admission.Rejected(RejectReason.StaleContext)
                tune(khz)
            }
            is RadioAction.ActivateSelection -> {
                val stations = current.stations
                if (action.regionId != stations.activeRegionId || stations.selections.none { it.name == action.name })
                    return Admission.Rejected(RejectReason.StaleContext)
                publish(current.copy(stations = stations.copy(activeSelectionName = action.name)))
            }
            is RadioAction.ChangeSetting -> return changeSetting(action.change)
            is RadioAction.SetDeveloper -> publish(current.copy(diagnostics = current.diagnostics.copy(developerEnabled = action.enabled, detail = null)))
            RadioAction.RefreshDiagnostics -> publish(current.copy(diagnostics = current.diagnostics.copy(detail = DeveloperDetail(
                emptySet(), emptyList(), null, AudioMetrics(null, null, null, null, null, null, null, null, null),
                listOf("Синтетическая диагностика"), null, null))))
        }
        return Admission.Accepted
    }

    private fun changeSetting(change: SettingChange): Admission {
        val settings = current.settings
        when (change) {
            is SettingChange.Receiver -> {
                if (current.receiver.availableProfiles.none { it.id == change.id }) return Admission.Rejected(RejectReason.Unsupported)
                if (current.receiver.selected != change.id) {
                    checkingLink = false
                    val hasSeek = change.id == module.id
                    val receiver = current.receiver.copy(selected = change.id, connection = Connection.Observing,
                        features = if (hasSeek) setOf(Feature.Seek) else emptySet())
                    publish(current.copy(receiver = receiver, tuning = TuningView(null, null, current.tuning.saved, TuningPhase.Idle, null, null),
                        playback = PlaybackView(PlaybackStatus.Stopped, FocusEffect.None), diagnostics = current.diagnostics.copy(detail = null),
                        settings = settings.copy(seekLimitSeconds = if (hasSeek) SEEK_SECONDS else null, transitionSignal = if (hasSeek) SignalChoice.Off else null)))
                }
            }
            is SettingChange.Theme -> theme(change.value)
            else -> {
                val next = when (change) {
                    is SettingChange.PaletteChoice -> settings.copy(palette = change.value)
                    is SettingChange.ParkLabel -> settings.copy(parkShowLabel = change.show)
                    is SettingChange.ParkIcon -> settings.copy(parkLargeIcon = change.large)
                    is SettingChange.Marker -> settings.copy(scaleMarker = change.value)
                    is SettingChange.Autostart -> settings.copy(autostart = change.enabled)
                    is SettingChange.SeekLimit -> {
                        if (settings.seekLimitSeconds == null || change.seconds !in 10..60 || change.seconds % 5 != 0)
                            return Admission.Rejected(RejectReason.InvalidInput)
                        settings.copy(seekLimitSeconds = change.seconds)
                    }
                    is SettingChange.TransitionSignal -> {
                        if (settings.transitionSignal == null) return Admission.Rejected(RejectReason.Unsupported)
                        settings.copy(transitionSignal = change.value)
                    }
                }
                publish(current.copy(settings = next))
            }
        }
        return Admission.Accepted
    }
}
