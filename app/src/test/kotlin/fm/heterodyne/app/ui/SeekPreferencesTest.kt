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
class SeekPreferencesTest {
    @Test fun defaultAndSelectionSurviveNewStore() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("seek-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = SeekPreferences(prefs)
        assertEquals(20, store.seconds)
        store.selectSeconds(35)
        assertEquals(35, store.seconds)
        assertEquals(35, SeekPreferences(prefs).seconds)
        store.selectSeconds(10)
        assertEquals(10, SeekPreferences(prefs).seconds)
        store.selectSeconds(60)
        assertEquals(60, SeekPreferences(prefs).seconds)
    }
    @Test fun invalidSavedLimitUsesDefaultAndInvalidSelectionIsRejected() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("seek-invalid", Context.MODE_PRIVATE)
        for (invalid in listOf(0, 9, 12, 65)) {
            prefs.edit().clear().putInt("seekLimitSeconds", invalid).commit()
            val store = SeekPreferences(prefs)
            assertEquals(20, store.seconds)
            assertThrows(IllegalArgumentException::class.java) { store.selectSeconds(invalid) }
            assertEquals(20, store.seconds)
        }
    }
}
