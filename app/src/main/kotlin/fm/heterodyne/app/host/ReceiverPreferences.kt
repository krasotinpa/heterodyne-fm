package fm.heterodyne.app.host

import android.content.SharedPreferences
import fm.heterodyne.app.receiver.ReceiverKind

/** Хранит вид Приёмника. Не открывает USB и не запускает звук. */
class ReceiverPreferences(private val preferences: SharedPreferences) {
    var kind = preferences.getString("kind", null)?.let {
        runCatching { ReceiverKind(it) }.getOrNull()
    }
        private set

    fun selectKind(value: ReceiverKind) {
        preferences.edit().putString("kind", value.value).apply()
        kind = value
    }
}
