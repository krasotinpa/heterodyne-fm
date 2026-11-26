package fm.heterodyne.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.heterodyne.ui.contract.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** All screen data and user intents cross RadioUi. The host invalidates tools work on disposal. */
@Composable
fun RadioContent(radio: RadioUi, modifier: Modifier = Modifier, onToolsClosed: () -> Unit = {}) {
    val state by radio.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var rejected by remember(radio, state.mode, state.receiver.selected, state.stations.activeRegionId,
        state.stations.activeSelectionName) { mutableStateOf<RejectReason?>(null) }
    val send: (RadioAction) -> Unit = { action ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val admission = radio.dispatch(action)
            // Background refreshes do not replace feedback from a user gesture.
            if (action != RadioAction.RefreshDiagnostics)
                rejected = (admission as? Admission.Rejected)?.reason
        }
    }
    HeterodyneTheme(state.settings.resolvedTheme, state.settings.palette) {
        Column(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (state.mode == ScreenMode.Efir) {
                    EfirScreen(state, send)
                    DeveloperBadge(state.diagnostics.developerEnabled, Modifier.align(Alignment.BottomEnd))
                } else ParkingScreen(state, onToolsClosed, send)
            }
            (rejected ?: state.actionIssue?.reason)?.let { Label(it.text(), 18) }
        }
    }
}
