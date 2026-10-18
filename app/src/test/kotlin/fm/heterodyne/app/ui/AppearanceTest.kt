package fm.heterodyne.app.ui

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun disabledControlsKeepReadableColorsEvenWhenSelected() {
        assertEquals(ControlColorTokens("surface", "disabledText"), controlColorTokens(enabled = false, selected = true))
        assertEquals(ControlColorTokens("surface", "disabledText"), controlColorTokens(enabled = false, selected = false))
        assertEquals(ControlColorTokens("accent", "onAccent"), controlColorTokens(enabled = true, selected = true))
        assertEquals(ControlColorTokens("surface2", "text"), controlColorTokens(enabled = true, selected = false))
    }

    @Test fun automaticThemeUsesHeadUnitAndFallsBackToDark() {
        assertEquals(ResolvedTheme.Dark, resolveTheme(ThemeChoice.Auto, null))
        assertEquals(ResolvedTheme.Dark, resolveTheme(ThemeChoice.Auto, true))
        assertEquals(ResolvedTheme.Light, resolveTheme(ThemeChoice.Auto, false))
        assertEquals(ResolvedTheme.Dark, resolveTheme(ThemeChoice.Dark, false))
        assertEquals(ResolvedTheme.Light, resolveTheme(ThemeChoice.Light, true))
        assertEquals(ResolvedTheme.Light, resolveTheme(ThemeChoice.Light, null))
        assertEquals(ResolvedTheme.Dark, resolveTheme(ThemeChoice.Dark, null))
    }
}
