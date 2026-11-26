package fm.heterodyne.app.ui

import fm.heterodyne.app.host.ResolvedTheme
import fm.heterodyne.app.host.ThemeChoice
import fm.heterodyne.app.host.resolveTheme
import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
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
