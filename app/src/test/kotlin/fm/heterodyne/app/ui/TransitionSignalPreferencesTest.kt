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
    @Test fun defaultAndSelectionSurviveNewStore() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("signal-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = TransitionSignalPreferences(prefs)
        assertEquals(TransitionSignalMethod.Tone, store.method)
        assertEquals(listOf("Выключено", "Тон", "Звук клавиши", "Тишина"), TransitionSignalMethod.entries.map { it.label })
        for (method in TransitionSignalMethod.entries) {
            store.selectMethod(method)
            assertEquals(method, store.method)
            assertEquals(method, TransitionSignalPreferences(prefs).method)
            assertEquals(method, TransitionSignalPreferences.readMethod(prefs))
        }
    }
    @Test fun unknownAndWrongTypeUseTone() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("signal-invalid", Context.MODE_PRIVATE)
        for (invalid in listOf("", "garbage", "999")) {
            prefs.edit().clear().putString("method", invalid).commit()
            assertEquals(TransitionSignalMethod.Tone, TransitionSignalPreferences(prefs).method)
        }
        prefs.edit().putInt("method", 1).commit()
        assertEquals(TransitionSignalMethod.Tone, TransitionSignalPreferences.readMethod(prefs))
    }
}
