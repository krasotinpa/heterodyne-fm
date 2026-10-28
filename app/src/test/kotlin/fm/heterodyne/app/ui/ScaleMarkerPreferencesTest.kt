package fm.heterodyne.app.ui

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ScaleMarkerPreferencesTest {
    private fun preferences(saved: MutableMap<String, String>): SharedPreferences {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { saved[args!![0] as String] = args[1] as String; proxy }
                "apply" -> null
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> saved[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
    }

    @Test fun defaultsToPinAndEverySelectionAppliesImmediatelyAndSurvivesReload() {
        val saved = mutableMapOf<String, String>()
        val prefs = preferences(saved)
        val store = ScaleMarkerPreferences(prefs)
        assertEquals(ScaleMarker.Pin, store.marker)
        assertTrue(saved.isEmpty())
        ScaleMarker.entries.forEach { marker ->
            store.selectMarker(marker)
            assertEquals(marker, store.marker)
            assertEquals(marker, ScaleMarkerPreferences(prefs).marker)
        }
    }

    @Test fun unknownSavedShapeFallsBackToPin() {
        assertEquals(ScaleMarker.Pin, ScaleMarkerPreferences(preferences(mutableMapOf("marker" to "future"))).marker)
    }
}
