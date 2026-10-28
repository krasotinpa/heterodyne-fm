package fm.heterodyne.app.ui

import android.app.Application
import android.content.Context
import fm.heterodyne.app.audio.TransitionSignalMethod
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TransitionSignalPreferencesTest {
    @Test fun defaultIsKeyClickAndSelectionSurvivesNewStore() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("signal-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = TransitionSignalPreferences(prefs)
        assertEquals(TransitionSignalMethod.KeyClick, store.method)
        assertEquals(TransitionSignalMethod.KeyClick, TransitionSignalPreferences.readMethod(prefs))
        assertFalse(prefs.contains("method"))
        assertEquals(listOf("Выключено", "Тон", "Звук клавиши", "Тишина"), TransitionSignalMethod.entries.map { it.label })
        for (method in TransitionSignalMethod.entries) {
            store.selectMethod(method)
            assertEquals(method, store.method)
            assertEquals(method, TransitionSignalPreferences(prefs).method)
            assertEquals(method, TransitionSignalPreferences.readMethod(prefs))
            assertEquals(method.name, prefs.getString("method", null))
        }
    }
    @Test fun unknownAndWrongTypeUseKeyClick() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("signal-invalid", Context.MODE_PRIVATE)
        for (invalid in listOf("", "garbage", "999")) {
            prefs.edit().clear().putString("method", invalid).commit()
            assertEquals(TransitionSignalMethod.KeyClick, TransitionSignalPreferences(prefs).method)
            assertEquals(TransitionSignalMethod.KeyClick, TransitionSignalPreferences.readMethod(prefs))
            assertEquals(invalid, prefs.getString("method", null))
        }
        prefs.edit().putInt("method", 1).commit()
        assertEquals(TransitionSignalMethod.KeyClick, TransitionSignalPreferences.readMethod(prefs))
        assertEquals(TransitionSignalMethod.KeyClick, TransitionSignalPreferences(prefs).method)
        assertEquals(1, prefs.getInt("method", 0))
    }
}
