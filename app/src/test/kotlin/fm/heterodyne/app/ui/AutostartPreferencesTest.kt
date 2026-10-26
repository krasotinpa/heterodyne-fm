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
class AutostartPreferencesTest {
    @Test fun defaultAndSelectionSurviveNewStore() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("autostart", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AutostartPreferences(prefs)
        assertTrue(store.enabled)
        store.selectEnabled(false)
        assertFalse(store.enabled)
        assertFalse(AutostartPreferences(prefs).enabled)
        store.selectEnabled(true)
        assertTrue(store.enabled)
        assertTrue(AutostartPreferences(prefs).enabled)
    }
}
