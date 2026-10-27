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
class ParkButtonPreferencesTest {
    @Test fun defaultsAndSelectionsSurviveNewStore() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("park-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = ParkButtonPreferences(prefs)
        assertTrue(store.showLabel)
        assertFalse(store.largeIcon)
        store.selectShowLabel(false)
        store.selectLargeIcon(true)
        assertFalse(store.showLabel)
        assertTrue(store.largeIcon)
        assertFalse(ParkButtonPreferences(prefs).showLabel)
        assertTrue(ParkButtonPreferences(prefs).largeIcon)
        store.selectShowLabel(true)
        store.selectLargeIcon(false)
        assertTrue(ParkButtonPreferences(prefs).showLabel)
        assertFalse(ParkButtonPreferences(prefs).largeIcon)
    }
}
