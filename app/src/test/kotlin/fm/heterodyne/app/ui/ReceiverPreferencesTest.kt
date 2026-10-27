package fm.heterodyne.app.ui

import android.app.Application
import android.content.Context
import fm.heterodyne.app.receiver.ReceiverKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReceiverPreferencesTest {
    @Test fun choiceSurvivesNewStoreAndInvalidSavedKindIsIgnored() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("receiver-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = ReceiverPreferences(prefs)
        assertNull(store.kind)
        store.selectKind(ReceiverKind("test"))
        assertEquals(ReceiverKind("test"), ReceiverPreferences(prefs).kind)
        prefs.edit().putString("kind", "INVALID!").commit()
        assertNull(ReceiverPreferences(prefs).kind)
    }
}
