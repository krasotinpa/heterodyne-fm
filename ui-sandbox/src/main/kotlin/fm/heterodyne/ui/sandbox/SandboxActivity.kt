package fm.heterodyne.ui.sandbox

import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fm.heterodyne.ui.compose.RadioContent
import fm.heterodyne.ui.contract.FocusEffect
import fm.heterodyne.ui.contract.ResolvedTheme

class SandboxActivity : ComponentActivity() {
    private val session by viewModels<SandboxSession>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Sandbox(session) }
    }
}

internal class SandboxSession : ViewModel() {
    var radio by mutableStateOf(ScriptedRadioUi(Scenario.Working))
        private set
    private var step by mutableIntStateOf(0)
    var time by mutableLongStateOf(0L)
        private set
    val nextCue get() = radio.scenario.cues.getOrNull(step)

    fun start(scenario: Scenario) {
        radio = ScriptedRadioUi(scenario)
        step = 0
        time = 0
    }

    fun advanceBy(millis: Long) {
        val end = time + millis
        while (true) {
            val cue = nextCue ?: break
            val at = cue.atMillis ?: break
            if (at > end) break
            radio.advanceBy(at - radio.elapsedMillis)
            cue.apply(radio)
            step++
        }
        radio.advanceBy(end - radio.elapsedMillis)
        time = end
    }

    fun next() {
        val cue = nextCue ?: return
        if (cue.atMillis == null) {
            cue.apply(radio)
            step++
        } else advanceBy(cue.atMillis - time)
    }
}

@Composable
private fun Sandbox(session: SandboxSession) {
    val radio = session.radio
    var choosing by rememberSaveable { mutableStateOf(false) }
    key(radio) {
        val state by radio.state.collectAsState()
        val scenario = radio.scenario
        val cue = session.nextCue
        Column(Modifier.fillMaxSize().background(Color(0xff212121)).systemBarsPadding()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SandboxButton("Сценарии") { choosing = !choosing }
                if (!choosing) {
                    SandboxLabel(scenario.title)
                    SandboxLabel("${session.time} мс")
                    SandboxButton("+1 с") { session.advanceBy(1000) }
                    if (cue != null) SandboxButton("Далее · ${cue.label}", Modifier.testTag("next-cue"), session::next)
                    SandboxButton("Сначала") { session.start(scenario) }
                    if (scenario == Scenario.Focus) SandboxLabel(when (state.playback.focus) {
                        FocusEffect.Muted -> "Без звука"
                        FocusEffect.Ducking -> "Тише"
                        FocusEffect.Normal -> "Обычная громкость"
                        FocusEffect.PermanentLossContinuing -> "Постоянная потеря"
                        else -> "Нет фокуса"
                    })
                    if (scenario == Scenario.Themes) SandboxLabel(if (state.settings.resolvedTheme == ResolvedTheme.Light) "Светлая" else "Тёмная")
                }
            }
            if (choosing) {
                BackHandler { choosing = false }
                LazyVerticalGrid(columns = GridCells.Adaptive(270.dp), contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Scenario.entries.size) { index ->
                        val choice = Scenario.entries[index]
                        SandboxButton(choice.title) { session.start(choice); choosing = false }
                    }
                }
            } else RadioContent(radio, Modifier.testTag("sandbox-radio"))
        }
    }
}

@Composable
private fun SandboxButton(label: String, modifier: Modifier = Modifier, invoke: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).background(Color(0xff424242)).clickable(role = Role.Button, onClick = invoke)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { SandboxLabel(label) }
}

@Composable
private fun SandboxLabel(text: String) = BasicText(text, style = TextStyle(color = Color.White, fontSize = 16.sp))
