package fm.heterodyne.app.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Stores the next launch preference; changing it never starts playback. */
class AutostartPreferences(private val preferences: SharedPreferences) {
    var enabled by mutableStateOf(preferences.getBoolean("enabled", true))
        private set

    fun selectEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        enabled = value
    }
}
