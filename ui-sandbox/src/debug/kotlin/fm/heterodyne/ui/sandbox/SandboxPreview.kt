package fm.heterodyne.ui.sandbox

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import fm.heterodyne.ui.compose.RadioSnapshot
import fm.heterodyne.ui.contract.ResolvedTheme
import fm.heterodyne.ui.contract.ThemeChoice

private val working = ScriptedRadioUi(Scenario.Working).state.value
private const val screen = "spec:width=1280dp,height=720dp,dpi=160"

@Preview(name = "Светлая · 1,0", device = screen, locale = "ru", apiLevel = 35, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Светлая · 1,3", device = screen, locale = "ru", apiLevel = 35, uiMode = Configuration.UI_MODE_NIGHT_NO, fontScale = 1.3f)
@Preview(name = "Тёмная · 1,0", device = screen, locale = "ru", apiLevel = 35, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Тёмная · 1,3", device = screen, locale = "ru", apiLevel = 35, uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 1.3f)
@Composable
private fun WorkingReceiverPreview() {
    val dark = isSystemInDarkTheme()
    val settings = working.settings.copy(theme = if (dark) ThemeChoice.Dark else ThemeChoice.Light,
        resolvedTheme = if (dark) ResolvedTheme.Dark else ResolvedTheme.Light)
    RadioSnapshot(working.copy(settings = settings), send = {})
}
