package fm.heterodyne.app.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fm.heterodyne.app.receiver.ReceiverKind

/** Хранит вид Приёмника. Не открывает USB и не запускает звук. */
class ReceiverPreferences(private val preferences: SharedPreferences) {
    var kind by mutableStateOf(preferences.getString("kind", null)?.let {
        runCatching { ReceiverKind(it) }.getOrNull()
    })
        private set

    fun selectKind(value: ReceiverKind) {
        preferences.edit().putString("kind", value.value).apply()
        kind = value
    }
}
