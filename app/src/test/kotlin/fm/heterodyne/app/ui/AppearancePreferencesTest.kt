package fm.heterodyne.app.ui

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppearancePreferencesTest {
    @Test fun choicesUpdateImmediatelyAndSurviveNewStore() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("appearance-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AppearancePreferences(prefs)
        assertEquals(ThemeChoice.Auto, store.theme)
        assertEquals(Palette.Coral, store.palette)
        store.selectTheme(ThemeChoice.Light)
        store.selectPalette(Palette.Plum)
        assertEquals(ThemeChoice.Light, store.theme)
        assertEquals(Palette.Plum, store.palette)
        val restored = AppearancePreferences(prefs)
        assertEquals(ThemeChoice.Light, restored.theme)
        assertEquals(Palette.Plum, restored.palette)
    }

    @Test fun unknownSavedChoicesUseSafeDefaults() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("appearance-invalid", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("theme", "unknown").putString("palette", "unknown").commit()
        val store = AppearancePreferences(prefs)
        assertEquals(ThemeChoice.Auto, store.theme)
        assertEquals(Palette.Coral, store.palette)
    }
}
