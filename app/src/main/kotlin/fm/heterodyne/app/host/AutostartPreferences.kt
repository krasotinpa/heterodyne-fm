package fm.heterodyne.app.host

import android.content.SharedPreferences

/** Stores the next launch preference; changing it never starts playback. */
class AutostartPreferences(private val preferences: SharedPreferences) {
    var enabled = preferences.getBoolean("enabled", true)
        private set

    fun selectEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        enabled = value
    }
}
