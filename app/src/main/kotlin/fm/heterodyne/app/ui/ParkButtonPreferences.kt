package fm.heterodyne.app.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Appearance only; selections apply immediately and survive a new launch. */
class ParkButtonPreferences(private val preferences: SharedPreferences) {
    var showLabel by mutableStateOf(preferences.getBoolean("showLabel", true))
        private set
    var largeIcon by mutableStateOf(preferences.getBoolean("largeIcon", false))
        private set

    fun selectShowLabel(value: Boolean) {
        preferences.edit().putBoolean("showLabel", value).apply()
        showLabel = value
    }

    fun selectLargeIcon(value: Boolean) {
        preferences.edit().putBoolean("largeIcon", value).apply()
        largeIcon = value
    }
}
