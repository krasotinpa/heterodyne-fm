package fm.heterodyne.app.host

import android.content.SharedPreferences

/** Appearance only; selections apply immediately and survive a new launch. */
class ParkButtonPreferences(private val preferences: SharedPreferences) {
    var showLabel = preferences.getBoolean("showLabel", true)
        private set
    var largeIcon = preferences.getBoolean("largeIcon", false)
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
