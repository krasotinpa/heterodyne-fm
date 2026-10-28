package fm.heterodyne.app.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fm.heterodyne.app.audio.TransitionSignalMethod

/** Способ Сигнала Перехода сохраняется независимо от подключения Приёмника. */
class TransitionSignalPreferences(private val preferences: SharedPreferences) {
    var method by mutableStateOf(readMethod(preferences))
        private set

    fun selectMethod(value: TransitionSignalMethod) {
        preferences.edit().putString("method", value.name).apply()
        method = value
    }

    companion object {
        fun readMethod(preferences: SharedPreferences): TransitionSignalMethod {
            val saved = try { preferences.getString("method", null) } catch (_: ClassCastException) { null }
            return TransitionSignalMethod.entries.firstOrNull { it.name == saved } ?: TransitionSignalMethod.KeyClick
        }
    }
}
