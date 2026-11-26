package fm.heterodyne.app.host

import android.content.SharedPreferences

enum class ScaleMarker(val title: String) {
    Pin("Булавка"), Flag("Флажок"), Needle("Игла"), Drop("Капля"), Stripe("Полоска")
}

/** Appearance only; selecting a marker never changes the tuned frequency. */
class ScaleMarkerPreferences(private val preferences: SharedPreferences) {
    var marker = ScaleMarker.entries.firstOrNull {
        it.name == preferences.getString("marker", null)
    } ?: ScaleMarker.Pin
        private set

    fun selectMarker(value: ScaleMarker) {
        preferences.edit().putString("marker", value.name).apply()
        marker = value
    }
}
