package fm.heterodyne.consumer

import fm.heterodyne.ui.contract.Admission
import fm.heterodyne.ui.contract.Connection
import fm.heterodyne.ui.contract.Feature
import fm.heterodyne.ui.contract.RadioAction
import fm.heterodyne.ui.contract.RadioUi
import fm.heterodyne.ui.contract.RadioUiState
import fm.heterodyne.ui.contract.ReceiverView
import fm.heterodyne.ui.contract.StationView
import fm.heterodyne.ui.contract.TuningPhase
import fm.heterodyne.ui.contract.TuningView
import kotlinx.coroutines.flow.StateFlow

internal class ContractClient(private val radio: RadioUi) {
    private val state: StateFlow<RadioUiState> = radio.state

    fun confirmedFrequencyKhz(): Int? = state.value.tuning.confirmed?.frequencyKhz

    fun savedFrequencyKhz(): Int? = state.value.tuning.saved?.frequencyKhz

    fun supportsSeek(): Boolean = state.value.receiver.let {
        it.connection == Connection.Observing && Feature.Seek in it.features
    }

    suspend fun tuneTo(frequencyKhz: Int): Admission =
        radio.dispatch(RadioAction.TuneFrequency(frequencyKhz))
}


// A JVM caller can construct fixture values for a saved frequency without a live receiver.
internal fun disconnectedReceiver() =
    ReceiverView(selected = null, availableProfiles = emptyList(), connection = Connection.Disconnected, features = emptySet())

internal fun disconnectedTuning(savedFrequencyKhz: Int) =
    TuningView(confirmed = null, targetKhz = null, saved = StationView(savedFrequencyKhz, null),
        phase = TuningPhase.Disconnected, pause = null, wheelSeekHintSeconds = null)
