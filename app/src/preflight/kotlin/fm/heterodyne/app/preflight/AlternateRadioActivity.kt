package fm.heterodyne.app.preflight

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.heterodyne.app.MainActivity
import fm.heterodyne.ui.contract.Control
import fm.heterodyne.ui.contract.RadioAction
import fm.heterodyne.ui.contract.RadioUi
import kotlinx.coroutines.launch

private const val TARGET_FREQUENCY_KHZ = 97_700

/** A second client of the real Application Adapter, available only in preflight. */
class AlternateRadioActivity : MainActivity() {
    @Composable
    override fun RenderRadio(radio: RadioUi, onToolsClosed: () -> Unit) {
        val state by radio.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            BasicText(state.tuning.confirmed?.let { "${it.frequencyKhz} kHz" } ?: "Waiting for Receiver")
            BasicText(state.playback.status.name)
            BasicText("Tune to $TARGET_FREQUENCY_KHZ kHz",
                Modifier.clickable(enabled = Control.TuneFrequency in state.enabled) {
                    scope.launch { radio.dispatch(RadioAction.TuneFrequency(TARGET_FREQUENCY_KHZ)) }
                }.padding(12.dp))
            BasicText("Stop", Modifier.clickable(enabled = Control.Stop in state.enabled) {
                scope.launch { radio.dispatch(RadioAction.Stop) }
            }.padding(12.dp))
        }
    }
}
